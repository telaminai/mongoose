package com.telamin.mongoose.control.validation;

import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exported control-audit evidence, read back from the analyser-framed YAML text alone: never from implementation
 * state. Each record is one node's entry inside a Fluxtion event-log record (or a fallback record the group wrote).
 * Only entries that carry a {@code record} key are control records; method-trace entries are skipped.
 */
public final class ControlEvidence {

    /** One control record: its type, the node that wrote it (or "fallback"), and its fields. */
    public record Rec(String type, String node, Map<String, Object> fields) {

        public long seq() {
            return num("seq");
        }

        public long requestId() {
            return num("requestId");
        }

        public long num(String key) {
            Object v = fields.get(key);
            return v == null ? -1 : ((Number) v).longValue();
        }

        public String str(String key) {
            Object v = fields.get(key);
            return v == null ? null : String.valueOf(v);
        }

        public boolean fallback() {
            return "fallback".equals(node);
        }

        @Override
        public String toString() {
            return type + "@" + node + fields;
        }
    }

    private static final Pattern NODE_ENTRY = Pattern.compile("^\\s+- ([A-Za-z0-9_$]+): (\\{.*})\\s*$");

    private final List<Rec> records;
    private final boolean complete;

    private ControlEvidence(List<Rec> records, boolean complete) {
        this.records = Collections.unmodifiableList(records);
        this.complete = complete;
    }

    /**
     * @param complete what the sink said about itself; an incomplete record makes evidence-dependent invariants
     *                 unverifiable rather than passing
     */
    public static ControlEvidence parse(String exportText, boolean complete) {
        Yaml yaml = new Yaml();
        List<Rec> out = new ArrayList<>();
        for (String doc : exportText.split("(?m)^---\\s*$")) {
            String text = doc.strip();
            if (text.isEmpty()) continue;
            if (text.startsWith("controlFallback:")) {
                Map<String, Object> root = yaml.load(text);
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = new LinkedHashMap<>((Map<String, Object>) root.get("controlFallback"));
                out.add(new Rec(String.valueOf(fields.get("record")), "fallback", fields));
                continue;
            }
            // strict: every exported document must load as YAML, as the analyser's reader loads it
            Map<String, Object> root;
            try {
                root = yaml.load(text);
            } catch (RuntimeException e) {
                throw new IllegalStateException("exported audit document is not valid YAML: " + e.getMessage() + "\n" + text, e);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> record = root == null ? null : (Map<String, Object>) root.get("eventLogRecord");
            if (record == null) {
                throw new IllegalStateException("exported audit document is neither an eventLogRecord nor a fallback:\n" + text);
            }
            Object nodeLogs = record.get("nodeLogs");
            if (!(nodeLogs instanceof List<?> entries)) continue;
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> one) || one.size() != 1) continue;
                Map.Entry<?, ?> e = one.entrySet().iterator().next();
                if (!(e.getValue() instanceof Map<?, ?> f) || !f.containsKey("record")) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = new LinkedHashMap<>((Map<String, Object>) f);
                out.add(new Rec(String.valueOf(fields.get("record")), String.valueOf(e.getKey()), fields));
            }
        }
        return new ControlEvidence(out, complete);
    }

    public List<Rec> records() {
        return records;
    }

    public boolean complete() {
        return complete;
    }

    public List<Rec> ofType(String type) {
        return records.stream().filter(r -> r.type().equals(type)).toList();
    }

    /** The effects a Decision requested, parsed from its {@code effects} field: opId, effect, generation. */
    public record Requested(String opId, String effect, long generation, long decisionSeq, long requestId) { }

    public List<Requested> requested() {
        List<Requested> out = new ArrayList<>();
        for (Rec d : ofType("Decision")) {
            String list = d.str("effects");
            if (list == null || list.isBlank()) continue;
            for (String item : list.split(";")) {
                Matcher m = Pattern.compile("^(\\S+) (\\w+)\\((-?\\d+)\\)$").matcher(item.strip());
                if (!m.matches()) throw new IllegalStateException("unparseable effect '" + item + "' in " + d);
                // an op id is requestId.n: a decision may answer another request (an audit start waiting on an open
                // its stop cancelled), and the op id says which
                String op = m.group(1);
                int dot = op.indexOf('.');
                long requestId = dot > 0 ? Long.parseLong(op.substring(0, dot)) : d.requestId();
                out.add(new Requested(op, m.group(2), Long.parseLong(m.group(3)), d.seq(), requestId));
            }
        }
        return out;
    }
}
