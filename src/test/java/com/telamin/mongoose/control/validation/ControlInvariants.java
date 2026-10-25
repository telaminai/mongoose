package com.telamin.mongoose.control.validation;

import com.telamin.mongoose.control.validation.ControlEvidence.Rec;
import com.telamin.mongoose.control.validation.ControlEvidence.Requested;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The control plane's invariants, written from the design's rules (docs/design/control-plane-as-events.md, "Rules" and
 * "Invariant validation") as predicates over exported audit records. They never read implementation state, and they
 * judge only what the evidence shows: on an incomplete record, invariants that need the missing evidence are reported
 * as unverifiable, never as passing.
 */
public final class ControlInvariants {

    private static final Set<String> COMMAND_FACTS = Set.of("Add", "Stop", "Register", "Deregister", "Run", "Close",
            "AuditStart", "AuditStop");
    /** Fallback records that answer a request (written by the group when it completes one outside the processor). */
    private static final Set<String> FALLBACK_REPLIES = Set.of("NotRunning", "Refused", "Failed", "APPLIED", "NOT_RUNNING",
            "REFUSED", "FAILED");

    private ControlInvariants() {
    }

    public record Result(List<String> violations, List<String> unverifiable) {
        public boolean ok() {
            return violations.isEmpty();
        }

        @Override
        public String toString() {
            return "violations=" + violations + (unverifiable.isEmpty() ? "" : " unverifiable=" + unverifiable);
        }
    }

    public static Result check(ControlEvidence evidence) {
        List<String> violations = new ArrayList<>();
        List<String> unverifiable = new ArrayList<>();
        if (!evidence.complete()) {
            unverifiable.add("I2, I3, I10: the record is incomplete (dropped or failed writes)");
        }
        Map<Long, List<Rec>> replies = new HashMap<>();
        for (Rec r : evidence.records()) {
            boolean delivered = r.type().equals("Reply") && !Boolean.FALSE.equals(r.fields().get("completed"));
            if (delivered || (r.fallback() && FALLBACK_REPLIES.contains(r.type()) && r.num("requestId") >= 0)) {
                replies.computeIfAbsent(r.requestId(), k -> new ArrayList<>()).add(r);
            }
        }
        // I11: nothing happens for a request after its reply: the reply comes only once every effect it depends on has
        // its final outcome
        Map<Long, Long> replySeq = new HashMap<>();
        for (Rec r : evidence.ofType("Reply")) {
            if (!Boolean.FALSE.equals(r.fields().get("completed"))) replySeq.putIfAbsent(r.requestId(), r.seq());
        }
        for (Rec r : evidence.records()) {
            if (r.fallback() || r.type().equals("Reply") || Boolean.TRUE.equals(r.fields().get("cleanup"))) continue;
            Long at = replySeq.get(r.requestId());
            if (at != null && r.seq() > at && !"Input".equals(r.type())) {
                violations.add("I11 request " + r.requestId() + " has a " + r.type() + " (seq " + r.seq()
                        + ") after its reply (seq " + at + "): " + r);
            }
        }
        // I13: no command is replied twice, and the rules ask for its reply only once
        replies.forEach((req, list) -> {
            if (list.size() > 1) violations.add("I13 request " + req + " replied " + list.size() + " times: " + list);
        });
        Map<Long, Integer> replyRequests = new HashMap<>();
        for (Requested q : evidence.requested()) {
            if ("Reply".equals(q.effect())) replyRequests.merge(q.requestId(), 1, Integer::sum);
        }
        replyRequests.forEach((req, count) -> {
            if (count > 1) violations.add("I13 request " + req + " had its reply requested " + count + " times");
        });
        // I2: every command input has exactly one reply
        if (evidence.complete()) {
            for (Rec in : evidence.ofType("Input")) {
                if (!COMMAND_FACTS.contains(in.str("fact"))) continue;
                List<Rec> answer = replies.get(in.requestId());
                if (answer == null || answer.isEmpty()) {
                    violations.add("I2 request " + in.requestId() + " (" + in.str("fact") + ") has no reply");
                }
            }
        }
        // I3 and I9: every requested effect has exactly one final outcome, after its decision
        Map<String, List<Rec>> outcomes = new HashMap<>();
        for (Rec r : evidence.records()) {
            if ((r.type().equals("EffectOutcome") || r.type().equals("Reply")) && r.str("opId") != null) {
                outcomes.computeIfAbsent(r.str("opId"), k -> new ArrayList<>()).add(r);
            }
        }
        List<Requested> requested = evidence.requested();
        for (Requested q : requested) {
            List<Rec> found = outcomes.getOrDefault(q.opId(), List.of()).stream()
                    .filter(o -> !"pending".equals(o.str("outcome")) && !Boolean.TRUE.equals(o.fields().get("duplicate")))
                    .toList();
            if (found.size() > 1) {
                violations.add("I3 effect " + q.opId() + " " + q.effect() + " has " + found.size() + " final outcomes");
            } else if (found.isEmpty() && evidence.complete()) {
                violations.add("I3 effect " + q.opId() + " " + q.effect() + " has no final outcome");
            }
            for (Rec o : found) {
                if (!o.fallback() && o.seq() <= q.decisionSeq()) {
                    violations.add("I9 effect " + q.opId() + " outcome (seq " + o.seq() + ") precedes its decision (seq "
                            + q.decisionSeq() + ")");
                }
            }
        }
        // I12: the effects one decision requested get their outcomes in the order they were requested (a close releases
        // in add order)
        Map<Long, List<Requested>> byDecision = new HashMap<>();
        for (Requested q : requested) byDecision.computeIfAbsent(q.decisionSeq(), k -> new ArrayList<>()).add(q);
        for (List<Requested> batch : byDecision.values()) {
            long last = -1;
            for (Requested q : batch) {
                long at = outcomes.getOrDefault(q.opId(), List.of()).stream().filter(o -> !o.fallback())
                        .mapToLong(Rec::seq).min().orElse(-1);
                if (at < 0) continue;
                if (at < last) {
                    violations.add("I12 effect " + q.opId() + " " + q.effect() + " got its outcome (seq " + at
                            + ") before an effect requested ahead of it in the same decision");
                }
                last = Math.max(last, at);
            }
        }
        // I6: every effect of an add or stop targets only the generation that request was bound to
        Map<Long, Rec> inputs = new HashMap<>();
        for (Rec in : evidence.ofType("Input")) inputs.put(in.requestId(), in);
        for (Requested q : requested) {
            Rec in = inputs.get(q.requestId());
            if (in == null) continue;
            String fact = in.str("fact");
            if (("Add".equals(fact) || "Stop".equals(fact)) && in.num("generation") >= 0
                    && q.generation() != in.num("generation")) {
                violations.add("I6 request " + q.requestId() + " (" + fact + " gen " + in.num("generation")
                        + ") requested " + q.effect() + " on gen " + q.generation());
            }
        }
        // I8: Applied only after every effect of that request succeeded
        for (Rec reply : evidence.ofType("Reply")) {
            if (!"APPLIED".equals(reply.str("outcome"))) continue;
            for (Rec o : evidence.ofType("EffectOutcome")) {
                // a fallback record has no seq to order by; a failed physical close is recorded and never blocks a reply
                if (o.requestId() == reply.requestId() && !o.fallback() && o.seq() < reply.seq()
                        && "failed".equals(o.str("outcome")) && !"DeliverService".equals(o.str("effect"))
                        && !"CloseCapture".equals(o.str("effect"))) {
                    violations.add("I8 request " + reply.requestId() + " replied APPLIED after a failed "
                            + o.str("effect") + " (" + o.str("opId") + ")");
                }
            }
        }
        // I5: at most one started, unreleased generation per name
        Map<String, Long> live = new HashMap<>();
        for (Rec d : evidence.ofType("Decision")) {
            String rule = d.str("rule");
            String name = d.str("name");
            long gen = d.num("generation");
            if (name == null) continue;
            if ("started".equals(rule)) {
                Long other = live.putIfAbsent(name, gen);
                if (other != null && other != gen) {
                    violations.add("I5 name " + name + " started as gen " + gen + " while gen " + other + " is live");
                }
            } else if ("released".equals(rule) || "releasedWithErrors".equals(rule)) {
                live.remove(name, gen);
            }
        }
        // I1: no InstallCapture for a generation once a decision has moved it to Releasing
        Map<Long, Long> releasingFrom = new HashMap<>();
        for (Rec d : evidence.ofType("Decision")) {
            if ("stopStarted".equals(d.str("rule"))) releasingFrom.putIfAbsent(d.num("generation"), d.seq());
        }
        for (Requested q : requested) {
            if ("Release".equals(q.effect())) releasingFrom.putIfAbsent(q.generation(), q.decisionSeq());
        }
        for (Requested q : requested) {
            Long from = releasingFrom.get(q.generation());
            if ("InstallCapture".equals(q.effect()) && from != null && q.decisionSeq() >= from) {
                violations.add("I1 InstallCapture " + q.opId() + " on gen " + q.generation()
                        + " requested after its release began (decision seq " + from + ")");
            }
        }
        // I4: every capture resource opened (accepted or compensated) is closed exactly once; duplicate and unknown
        // completions and refused closes do not count as opens
        Map<Long, Integer> opened = new HashMap<>();
        Map<Long, Integer> closed = new HashMap<>();
        for (Rec d : evidence.ofType("Decision")) {
            String rule = d.str("rule");
            if ("captureOpened".equals(rule) || "captureOpenedStale".equals(rule)) opened.merge(d.num("resourceId"), 1, Integer::sum);
        }
        for (Rec r : evidence.records()) {
            boolean closeOutcome = "EffectOutcome".equals(r.type()) && "CloseCapture".equals(r.str("effect"))
                    && !"pending".equals(r.str("outcome")) && r.num("resourceId") >= 0
                    && !Boolean.TRUE.equals(r.fields().get("duplicate"));
            if (closeOutcome) closed.merge(r.num("resourceId"), 1, Integer::sum);
        }
        opened.forEach((resource, n) -> {
            if (n > 1) violations.add("I4/I14 resource " + resource + " accepted as opened " + n + " times");
            int c = closed.getOrDefault(resource, 0);
            if (c > 1) violations.add("I4 resource " + resource + " closed " + c + " times");
            if (c == 0 && evidence.complete()) violations.add("I4 resource " + resource + " opened and never closed");
        });
        // I14: a duplicate completion has no effect
        for (Rec d : evidence.ofType("Decision")) {
            if ("captureDuplicate".equals(d.str("rule")) || "captureCloseUnknown".equals(d.str("rule"))) {
                String effects = d.str("effects");
                if (effects != null && effects.contains("InstallCapture")) {
                    violations.add("I14 duplicate completion (seq " + d.seq() + ") installed a capture: " + effects);
                }
            }
        }
        // I10: control-record seq numbers are contiguous
        if (evidence.complete()) {
            long expected = 1;
            Set<Long> seen = new HashSet<>();
            for (Rec r : evidence.records()) {
                if (r.fallback()) continue;
                if (!seen.add(r.seq())) violations.add("I10 seq " + r.seq() + " repeated");
                if (r.seq() != expected) {
                    violations.add("I10 seq gap: expected " + expected + " found " + r.seq());
                    expected = r.seq();
                }
                expected++;
            }
        }
        return new Result(violations, unverifiable);
    }
}
