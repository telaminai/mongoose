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
import com.telamin.mongoose.service.error.ErrorReporting;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
        group.setIsolateStartFailuresAtBoot(true);
        Reservations reservations = new Reservations();
        List<LedgerProc> all = new ArrayList<>();
        Map<String, Integer> serials = new HashMap<>();
        List<String> script = new ArrayList<>();
        String[] names = {"a", "b", "c"};
        boolean started = false;
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
                int op = random.nextInt(10);
                String name = names[random.nextInt(names.length)];
                if (!started && (op == 9 || step == steps / 3)) {
                    group.onStart();
                    started = true;
                    script.add("start");
                } else if (op < 4) {
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
                    PendingAdd add = new PendingAdd(name, reg, proc, step + random.nextInt(3));
                    pending.add(add);
                    script.add("admit " + name + "#" + serial + (failStart ? " failStart" : "") + (failStop ? " failStop" : "")
                            + " enqueue@" + add.enqueueAt);
                } else if (op < 7) {
                    ProcessorRegistration reg = reservations.table.get(name);
                    if (reg == null) {
                        script.add("stop " + name + ": nothing reserved");
                        continue;
                    }
                    group.removeEventProcessorByName(name, reg, null);
                    for (PendingAdd add : pending) {
                        if (add.reg == reg) {
                            add.overtaken = true;
                            overtaken.add(add.proc);
                        }
                    }
                    script.add("stop " + name + " gen " + reg.generation());
                } else if (started) {
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
                group.onStart();
                script.add("start");
            }
            for (int i = 0; i < 200 && !group.control().isEmpty(); i++) group.doWork();
            group.onClose();
            script.add("close");

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
            }
            // scenario expectation from the rules table (stopBeforeAdd, addCancelled): an add whose stop was sent
            // before the add was queued never starts and never runs its processor
            for (LedgerProc p : overtaken) {
                assertEquals(0, p.starts.get(), "seed " + seed + ": " + p.name + "#" + p.serial
                        + " started although its stop overtook its add\n" + script);
            }
            assertTrue(reservations.table.isEmpty(), "seed " + seed + ": names still reserved after close: "
                    + reservations.table.keySet() + "\n" + script);
            assertEquals(0, group.control().outstanding(), "seed " + seed + ": requests left unanswered\n" + script);

            // ---- audit verdict, from the exported record alone
            ControlAuditSink audit = group.control().audit();
            ControlEvidence evidence = ControlEvidence.parse(audit.exportText(), audit.isComplete());
            ControlInvariants.Result result = ControlInvariants.check(evidence);
            if (!result.ok()) {
                Path out = Path.of("target/control-audit/harness-seed-" + seed + ".yaml");
                audit.export(out);
                fail("seed " + seed + " broke audit invariants: " + result + "\naudit: " + out.toAbsolutePath()
                        + "\n" + script);
            }
        } finally {
            if (group.control().state() != ControlDriver.GroupState.CLOSED) {
                try { group.onClose(); } catch (Throwable ignored) { }
            }
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
        ControlEvidence evidence = ControlEvidence.parse(text, true);
        assertTrue(ControlInvariants.check(evidence).ok(), ControlInvariants.check(evidence).toString());
        List<String> rules = evidence.ofType("Decision").stream().map(d -> d.str("rule")).toList();
        assertEquals(List.of("addAdmitted", "started", "stopStarted", "released", "stopComplete", "closeEmpty"), rules);
        assertEquals(1, proc.starts.get());
        assertEquals(1, proc.tearDowns.get());
        assertTrue(reservations.table.isEmpty());
        assertTrue(ErrorReporting.getReporter() != null);
    }
}
