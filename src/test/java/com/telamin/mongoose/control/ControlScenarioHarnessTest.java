package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.control.validation.ControlInvariants;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.dutycycle.ProcessorStartException;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.service.error.ErrorReporting;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The scenario harness (design "Development workflow", steps 2–4): seeded command sequences driven through a real
 * group and its generated control processor on one thread, with injected start and stop failures. Every run is judged
 * twice, independently:
 * <ul>
 *   <li>by the <b>resource ledger</b>: each simulated processor counts its own starts, stops and tear-downs, and the
 *       harness keeps its own reservation table exactly as the server does; the verdict comes from these counts, not
 *       from the audit's narration;</li>
 *   <li>by the <b>audit invariants</b> ({@link ControlInvariants}), read back from the exported record alone.</li>
 * </ul>
 * A failing seed is printed with the path of its exported audit, so it can be opened in the analyser and kept as a
 * regression test.
 */
class ControlScenarioHarnessTest {

    /** A group driven inline marks this thread as an agent; a failed test must not leak the mark into the next. */
    @AfterEach
    void clearAgentMark() {
        com.telamin.mongoose.control.AgentThreads.exit();
    }

    /** A simulated processor that keeps its own ledger. */
    static final class LedgerProc implements DataFlow, Lifecycle {
        final String name;
        final int serial;
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger tearDowns = new AtomicInteger();
        final boolean failStart;
        final boolean failStop;

        LedgerProc(String name, int serial, boolean failStart, boolean failStop) {
            this.name = name;
            this.serial = serial;
            this.failStart = failStart;
            this.failStop = failStop;
        }

        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }

        @Override
        public void start() {
            starts.incrementAndGet();
            if (failStart) throw new IllegalStateException("simulated start failure of " + name + "#" + serial);
        }

        @Override
        public void stop() {
            stops.incrementAndGet();
            if (failStop) throw new IllegalStateException("simulated stop failure of " + name + "#" + serial);
        }

        @Override
        public void tearDown() {
            tearDowns.incrementAndGet();
        }

        boolean running() {
            return starts.get() == 1 && !failStart && tearDowns.get() == 0;
        }
    }

    /** The harness's own copy of the server's reservation table: send-time admission, freed by the release effect. */
    static final class Reservations {
        final Map<String, ProcessorRegistration> table = new ConcurrentHashMap<>();

        ProcessorRegistration admit(String name) {
            ProcessorRegistration reg = new ProcessorRegistration(name);
            reg.nameReleaser(() -> table.remove(name, reg));
            return table.putIfAbsent(name, reg) == null ? reg : null;
        }
    }

    /** An add admitted but not yet enqueued; {@code overtaken} once a stop bound to it was sent first. */
    static final class PendingAdd {
        final String name;
        final ProcessorRegistration reg;
        final LedgerProc proc;
        final int enqueueAt;
        boolean overtaken;

        PendingAdd(String name, ProcessorRegistration reg, LedgerProc proc, int enqueueAt) {
            this.name = name;
            this.reg = reg;
            this.proc = proc;
            this.enqueueAt = enqueueAt;
        }
    }

    static LongStream seeds() {
        return LongStream.rangeClosed(1, 200);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void generatedScenarioKeepsLedgerAndAuditInvariants(long seed) throws Exception {
        Random random = new Random(seed);
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("harness", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        boolean failFastBoot = random.nextInt(4) == 0;
        group.setIsolateStartFailuresAtBoot(!failFastBoot);
        // audit capture as effects: every open, close and timeout runs when the scenario says
        CaptureRig.ManualIo io = new CaptureRig.ManualIo();
        CaptureRig.FakeResources captures = new CaptureRig.FakeResources();
        captures.running = flow -> flow instanceof LedgerProc p && p.running();
        group.setCaptureIo(io);
        group.setAuditCaptureResources(captures);
        Reservations reservations = new Reservations();
        List<LedgerProc> all = new ArrayList<>();
        Map<String, Integer> serials = new HashMap<>();
        Map<ProcessorRegistration, LedgerProc> procOf = new IdentityHashMap<>();
        List<ProcessorRegistration> everAdmitted = new ArrayList<>();
        Set<LedgerProc> legitimatelyStopped = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Map<ControlRequest, AtomicInteger> runs = new LinkedHashMap<>();
        Map<String, Long> versions = new HashMap<>();
        List<String> script = new ArrayList<>();
        script.add(failFastBoot ? "boot failures fail fast" : "boot failures isolated");
        String[] names = {"a", "b", "c"};
        boolean started = false;
        boolean closed = false;
        try {
            int steps = 20 + random.nextInt(40);
            List<PendingAdd> pending = new ArrayList<>();
            List<LedgerProc> overtaken = new ArrayList<>();
            for (int step = 0; step < steps; step++) {
                for (var it = pending.iterator(); it.hasNext(); ) {
                    PendingAdd add = it.next();
                    if (add.enqueueAt <= step) {
                        it.remove();
                        group.addNamedEventProcessor(() -> new NamedEventProcessor(add.name, add.proc), add.reg);
                        script.add("enqueue " + add.name + "#" + add.proc.serial + " gen " + add.reg.generation());
                    }
                }
                int op = random.nextInt(13);
                String name = names[random.nextInt(names.length)];
                if (!started && (op == 9 || step == steps / 3)) {
                    started = true;
                    closed = startOrClose(group, script);
                } else if (op < 3) {
                    // admission and enqueue are two steps on two threads in the server: a stop can land between them
                    ProcessorRegistration reg = reservations.admit(name);
                    if (reg == null) {
                        script.add("add " + name + " refused: name reserved");
                        continue;
                    }
                    int serial = serials.merge(name, 1, Integer::sum);
                    boolean failStart = random.nextInt(8) == 0;
                    boolean failStop = random.nextInt(8) == 0;
                    LedgerProc proc = new LedgerProc(name, serial, failStart, failStop);
                    all.add(proc);
                    procOf.put(reg, proc);
                    everAdmitted.add(reg);
                    PendingAdd add = new PendingAdd(name, reg, proc, step + random.nextInt(3));
                    pending.add(add);
                    script.add("admit " + name + "#" + serial + (failStart ? " failStart" : "") + (failStop ? " failStop" : "")
                            + " enqueue@" + add.enqueueAt);
                } else if (op < 5) {
                    ProcessorRegistration reg = reservations.table.get(name);
                    if (reg == null) {
                        script.add("stop " + name + ": nothing reserved");
                        continue;
                    }
                    if (random.nextInt(4) == 0) {
                        // a stop that only names the processor: resolved to whatever answers to the name when applied
                        group.removeEventProcessorByName(name);
                        for (ProcessorRegistration r : everAdmitted) {
                            if (name.equals(r.name())) legitimatelyStopped.add(procOf.get(r));
                        }
                        script.add("stop by name " + name);
                        continue;
                    }
                    group.removeEventProcessorByName(name, reg, null);
                    legitimatelyStopped.add(procOf.get(reg));
                    for (PendingAdd add : pending) {
                        if (add.reg == reg) {
                            add.overtaken = true;
                            overtaken.add(add.proc);
                        }
                    }
                    script.add("stop " + name + " gen " + reg.generation());
                } else if (op == 5 && !everAdmitted.isEmpty()) {
                    // a stop bound to a registration chosen from history: stale unless it is still the reserved one
                    ProcessorRegistration reg = everAdmitted.get(random.nextInt(everAdmitted.size()));
                    boolean current = reservations.table.get(reg.name()) == reg;
                    group.removeEventProcessorByName(reg.name(), reg, null);
                    if (current) legitimatelyStopped.add(procOf.get(reg));
                    script.add((current ? "stop " : "stale stop ") + reg.name() + " gen " + reg.generation());
                } else if (op == 6) {
                    String svc = "svc-" + random.nextInt(3);
                    Service<Runnable> service = new Service<>(() -> { }, Runnable.class, svc);
                    // versioned as the server sends them, sometimes arriving stale (an older version after a newer one)
                    long version = versions.merge(svc, 1L, Long::sum);
                    if (random.nextInt(5) == 0) version = Math.max(1, version - 1 - random.nextInt(2));
                    group.broadcastService(service, random.nextBoolean(), version);
                    script.add("broadcast " + svc + " v" + version);
                } else if (op == 7) {
                    AtomicInteger ran = new AtomicInteger();
                    ControlRequest run = group.control().send(new ControlRequest(ControlRequest.Kind.RUN, "task",
                            RunIntent::new, (Runnable) ran::incrementAndGet));
                    runs.put(run, ran);
                    script.add("run task " + run.requestId());
                } else if (op == 10 || op == 11) {
                    // an audit start or stop, bound to the reserved registration (or by name when none is)
                    ProcessorRegistration reg = reservations.table.get(name);
                    ControlRequest r = op == 10 ? group.auditStart(name, reg) : group.auditStop(name, reg);
                    script.add((op == 10 ? "audit start " : "audit stop ") + name + " req " + r.requestId());
                } else if (op == 12) {
                    // a helper or the timer acts: an open completes (in order or overtaking), a close completes, an
                    // open times out, or an open delivers its completion again
                    int which = random.nextInt(8);
                    boolean acted = switch (which) {
                        case 0 -> io.runOpen();
                        case 1 -> io.runLatestOpen();
                        case 2 -> io.runClose();
                        case 3 -> {
                            if (io.timeouts.isEmpty()) yield false;
                            io.fireTimeout(random.nextInt(io.timeouts.size()));
                            yield true;
                        }
                        case 4 -> io.rerunOpen(random.nextInt(8));
                        case 5 -> io.rejectNextOpen = true;         // the opener pool refuses the next open
                        case 6 -> io.rejectNextClose = true;        // the closer refuses: the group closes inline
                        default -> captures.failCloses = !captures.failCloses;
                    };
                    script.add("io " + new String[]{"open", "latest open", "close", "timeout", "duplicate open",
                            "reject next open", "reject next close", "toggle close failures"}[which]
                            + (acted ? "" : " (nothing)"));
                } else if (started && !closed) {
                    int cycles = 1 + random.nextInt(3);
                    for (int c = 0; c < cycles; c++) group.doWork();
                    script.add("doWork x" + cycles);
                    // ledger check at a quiescent point: never two running instances of one name
                    Map<String, Integer> runningByName = new HashMap<>();
                    for (LedgerProc p : all) if (p.running()) runningByName.merge(p.name, 1, Integer::sum);
                    runningByName.forEach((n, count) -> {
                        if (count > 1) fail("seed " + seed + ": " + count + " running instances of " + n + "\n" + script);
                    });
                }
            }
            for (PendingAdd add : pending) {
                group.addNamedEventProcessor(() -> new NamedEventProcessor(add.name, add.proc), add.reg);
                script.add("enqueue " + add.name + "#" + add.proc.serial + " gen " + add.reg.generation());
            }
            if (!started) {
                started = true;
                closed = startOrClose(group, script);
            }
            if (!closed && random.nextBoolean()) {
                // half the runs close with requests still queued: the close must answer them through the rules
                for (int i = 0; i < 200 && !group.control().isEmpty(); i++) group.doWork();
                script.add("drained");
            }
            Map<LedgerProc, Integer> stopsBeforeClose = new IdentityHashMap<>();
            for (LedgerProc p : all) stopsBeforeClose.put(p, p.stops.get());
            if (!closed) {
                group.onClose();
                closed = true;
                script.add("close");
            }
            // helpers still holding work finish after the close: their completions are orphans, compensated
            io.runAll();
            script.add("io drained");
            assertEquals("", captures.accountingErrors(), "seed " + seed + ": capture ledger\n" + script);
            assertEquals(0, group.captureResourcesHeld(), "seed " + seed + ": capture resources held\n" + script);

            // ---- ledger verdict: every instance started at most once, released exactly once if it started
            for (LedgerProc p : all) {
                String who = p.name + "#" + p.serial;
                assertTrue(p.starts.get() <= 1, "seed " + seed + ": " + who + " started " + p.starts.get() + " times\n" + script);
                assertTrue(p.stops.get() <= 1, "seed " + seed + ": " + who + " stopped " + p.stops.get() + " times\n" + script);
                assertTrue(p.tearDowns.get() <= 1, "seed " + seed + ": " + who + " torn down " + p.tearDowns.get() + " times\n" + script);
                if (p.starts.get() == 1) {
                    assertEquals(1, p.tearDowns.get(), "seed " + seed + ": " + who + " started but never torn down\n" + script);
                }
                if (p.failStart) {
                    assertEquals(0, p.stops.get(), "seed " + seed + ": " + who + " failed to start but was stopped\n" + script);
                }
                // rule 7: a stop affects only the registration it was bound to; a stale stop never stops a replacement
                if (stopsBeforeClose.get(p) > 0) {
                    assertTrue(legitimatelyStopped.contains(p), "seed " + seed + ": " + who
                            + " was stopped before the close, but no stop was bound to its registration\n" + script);
                }
            }
            // scenario expectation from the rules table (stopBeforeAdd, addCancelled): an add whose stop was sent
            // before the add was queued never starts and never runs its processor
            for (LedgerProc p : overtaken) {
                assertEquals(0, p.starts.get(), "seed " + seed + ": " + p.name + "#" + p.serial
                        + " started although its stop overtook its add\n" + script);
            }
            // a task runs exactly when it is answered APPLIED, and never otherwise
            for (Map.Entry<ControlRequest, AtomicInteger> e : runs.entrySet()) {
                ControlReply answer = e.getKey().reply().getNow(null);
                assertTrue(answer != null, "seed " + seed + ": task " + e.getKey().requestId() + " never answered\n" + script);
                assertEquals(answer.applied() ? 1 : 0, e.getValue().get(), "seed " + seed + ": task "
                        + e.getKey().requestId() + " answered " + answer + " but ran " + e.getValue().get() + " times\n" + script);
            }
            assertTrue(reservations.table.isEmpty(), "seed " + seed + ": names still reserved after close: "
                    + reservations.table.keySet() + "\n" + script);
            assertEquals(0, group.control().outstanding(), "seed " + seed + ": requests left unanswered\n" + script);

            // ---- audit verdict, from the exported record alone
            ControlAuditSink audit = group.control().audit();
            assertTrue(audit.isComplete(), "seed " + seed + ": incomplete audit cannot validate this scenario");
            ControlEvidence evidence = ControlEvidence.parse(audit.exportText(), audit.isComplete());
            Set<Long> recordedCloseFailures = evidence.ofType("EffectOutcome").stream()
                    .filter(r -> "CloseCapture".equals(r.str("effect")) && "failed".equals(r.str("outcome"))
                            && !Boolean.TRUE.equals(r.fields().get("duplicate")) && r.num("resourceId") >= 0)
                    .map(r -> r.num("resourceId")).collect(java.util.stream.Collectors.toSet());
            assertEquals(captures.failedCloseResources(), recordedCloseFailures,
                    "seed " + seed + ": physical ledger and audited close failures disagree\n" + script);
            ControlInvariants.Result result = ControlInvariants.check(evidence);
            Set<String> expectedUnknownClosures = captures.failedCloseResources().stream()
                    .map(id -> "I4/I11 resource " + id + " close failed; physical closure is not established")
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(expectedUnknownClosures, Set.copyOf(result.unverifiable()),
                    "seed " + seed + ": only ledger-confirmed physical close failures may remain unverifiable\n" + script);
            if (!result.ok()) {
                Path out = Path.of("target/control-audit/harness-seed-" + seed + ".yaml");
                audit.export(out);
                fail("seed " + seed + " broke audit invariants: " + result + "\naudit: " + out.toAbsolutePath()
                        + "\n" + script);
            }
        } finally {
            if (!closed) {
                try { group.onClose(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Start the group; a boot failure under fail-fast closes it, as Agrona does when onStart throws. */
    private static boolean startOrClose(ComposingEventProcessorAgent group, List<String> script) {
        try {
            group.onStart();
            script.add("start");
            return false;
        } catch (ProcessorStartException e) {
            group.onClose();
            script.add("start failed fast, group closed");
            return true;
        }
    }

    @Test
    void anExportedAuditReadsBackOneRecordPerDispatchWithIdentitiesIntact() throws Exception {
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("export", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        group.onStart();
        Reservations reservations = new Reservations();
        ProcessorRegistration reg = reservations.admit("p");
        LedgerProc proc = new LedgerProc("p", 1, false, false);
        group.addNamedEventProcessor(() -> new NamedEventProcessor("p", proc), reg);
        group.doWork();
        group.removeEventProcessorByName("p", reg, null);
        group.doWork();
        group.onClose();
        Path out = group.control().audit().export(Path.of("target/control-audit/export-roundtrip.yaml"));
        String text = Files.readString(out);
        assertTrue(text.startsWith("---\n") && text.endsWith("---\n"), "framed as the analyser frames records");
        ControlAuditExport.Verification completeness = ControlAuditExport.verify(out);
        assertTrue(completeness.permitsInvariantPass(), completeness.reason());
        ControlEvidence evidence = ControlEvidence.parse(text, completeness.permitsInvariantPass());
        assertTrue(ControlInvariants.check(evidence).unverifiable().isEmpty());
        assertTrue(ControlInvariants.check(evidence).ok(), ControlInvariants.check(evidence).toString());
        List<String> rules = evidence.ofType("Decision").stream().map(d -> d.str("rule")).toList();
        assertEquals(List.of("addAdmitted", "started", "stopStarted", "released", "stopComplete", "closeEmpty"), rules);
        assertEquals(1, proc.starts.get());
        assertEquals(1, proc.tearDowns.get());
        assertTrue(reservations.table.isEmpty());
        assertTrue(ErrorReporting.getReporter() != null);
    }
}
