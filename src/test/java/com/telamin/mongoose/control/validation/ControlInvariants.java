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

    private static final Set<String> COMMAND_FACTS = Set.of("Add", "Stop", "Register", "Deregister", "Run", "Close");
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
            if (r.type().equals("Reply") || (r.fallback() && FALLBACK_REPLIES.contains(r.type()) && r.num("requestId") >= 0)) {
                replies.computeIfAbsent(r.requestId(), k -> new ArrayList<>()).add(r);
            }
        }
        // I13: no command is replied twice
        replies.forEach((req, list) -> {
            if (list.size() > 1) violations.add("I13 request " + req + " replied " + list.size() + " times: " + list);
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
                    .filter(o -> !"pending".equals(o.str("outcome"))).toList();
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
                if (o.requestId() == reply.requestId() && o.seq() < reply.seq() && "failed".equals(o.str("outcome"))
                        && !"DeliverService".equals(o.str("effect"))) {
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
