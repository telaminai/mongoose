/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** File-only completeness check; a complete export still needs independent invariant validation. */
public final class ControlAuditExport {
    private ControlAuditExport() { }

    public enum Completeness { COMPLETE, INCOMPLETE, UNKNOWN }

    public record Verification(Completeness completeness, String reason) {
        /** Missing, invalid and incomplete evidence must never authorize an invariant PASS. */
        public boolean permitsInvariantPass() { return completeness == Completeness.COMPLETE; }
    }

    public static Path manifestPath(Path audit) {
        return audit.resolveSibling(audit.getFileName() + ".manifest.yaml");
    }

    static String manifest(byte[] yaml, long total, long retained, long dropped, long sinkFailures) {
        return "format: mongoose-control-audit\nversion: 1\nsha256: \"" + sha256(yaml) + "\""
                + "\ntotal: " + total + "\nretained: " + retained + "\ndropped: " + dropped
                + "\nsinkFailures: " + sinkFailures + "\ncomplete: " + (dropped == 0 && sinkFailures == 0) + "\n";
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
    }

    /** Reads only the exported YAML and its sidecar; it does not trust an in-memory sink or caller-supplied boolean. */
    public static Verification verify(Path audit) {
        try {
            byte[] yaml = Files.readAllBytes(audit);
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Yaml parser = new Yaml(new SafeConstructor(options));
            Object loaded = parser.load(Files.readString(manifestPath(audit), StandardCharsets.UTF_8));
            if (!(loaded instanceof Map<?, ?> m)
                    || !"mongoose-control-audit".equals(m.get("format")) || number(m, "version") != 1) {
                return unknown("missing or unsupported manifest format/version");
            }
            if (!sha256(yaml).equals(m.get("sha256"))) return unknown("audit SHA-256 does not match manifest");
            long total = number(m, "total"), retained = number(m, "retained"), dropped = number(m, "dropped");
            long failures = number(m, "sinkFailures");
            if (retained > total || total - retained != dropped) return unknown("inconsistent record counts");
            long documents = 0;
            for (Object doc : parser.loadAll(new String(yaml, StandardCharsets.UTF_8))) if (doc != null) documents++;
            if (documents != retained) return unknown("retained count does not match audit documents");
            boolean complete = dropped == 0 && failures == 0;
            if (!Boolean.valueOf(complete).equals(m.get("complete"))) return unknown("inconsistent completeness flag");
            return new Verification(complete ? Completeness.COMPLETE : Completeness.INCOMPLETE,
                    complete ? "complete snapshot; invariants still require validation"
                            : "snapshot lost records: dropped=" + dropped + ", sinkFailures=" + failures);
        } catch (IOException | RuntimeException invalid) {
            return unknown("unavailable or invalid export: " + invalid.getMessage());
        }
    }

    private static long number(Map<?, ?> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0) {
            throw new IllegalArgumentException("invalid manifest field " + key);
        }
        return ((Number) value).longValue();
    }

    private static Verification unknown(String reason) {
        return new Verification(Completeness.UNKNOWN, reason);
    }
}
