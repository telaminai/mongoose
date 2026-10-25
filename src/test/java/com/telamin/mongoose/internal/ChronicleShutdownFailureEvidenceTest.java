/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.control.CaptureIo;
import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.*;
import com.telamin.mongoose.service.audit.AuditCaptureIdentity;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.agrona.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ChronicleShutdownFailureEvidenceTest {
    static class Flow implements DataFlow {
        volatile LogRecordListener installed;
        public void onEvent(Object event) { }
        public void addEventFeed(EventFeed feed) { }
        public void setAuditLogProcessor(LogRecordListener listener) { installed = listener; }
    }
    @Test void failedChronicleCloseKeepsExactStopResultIncompleteAndProducesOperatorEvidence(@TempDir Path root) throws Exception {
        String groupName = "chronicle-close";
        String reason = "forced Chronicle close failure";
        String failure = "java.lang.IllegalStateException: " + reason;
        var logRecords = new CopyOnWriteArrayList<LogRecord>();
        Logger lifecycleLog = Logger.getLogger("com.telamin.mongoose");
        Handler observer = new Handler() {
            public void publish(LogRecord record) { logRecords.add(record); }
            public void flush() { }
            public void close() { }
        };
        lifecycleLog.addHandler(observer);
        var realQueue = new AtomicReference<ChronicleQueue>();
        var closeCalls = new AtomicInteger();
        var cfg = new AuditCaptureConfig(); cfg.setEnabled(true); cfg.setDirectory(root.toString());
        var resources = new ChronicleAuditCaptureService(cfg, NoOpCountersService.INSTANCE, path -> {
            ChronicleQueue queue = SingleChronicleQueueBuilder.binary(path).rollCycle(RollCycles.FAST_DAILY).build();
            realQueue.set(queue);
            return (ChronicleQueue) Proxy.newProxyInstance(ChronicleQueue.class.getClassLoader(),
                    new Class<?>[]{ChronicleQueue.class}, (proxy, method, args) -> {
                        if (method.getName().equals("close")) {
                            closeCalls.incrementAndGet();
                            throw new IllegalStateException(reason);
                        }
                        try { return method.invoke(queue, args); }
                        catch (InvocationTargetException thrown) { throw thrown.getCause(); }
                    });
        });
        var eventFlow = new EventFlowManager();
        var group = new ComposingEventProcessorAgent(groupName, eventFlow, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        group.setAuditCaptureResources(resources);
        // Inline physical I/O makes the injected failure settle before the shutdown helper reports its result.
        group.setCaptureIo(new CaptureIo() {
            public void open(Runnable task) { task.run(); }
            public void close(Runnable task) { task.run(); }
            public Cancellable schedule(Runnable task, long delay) { return () -> { }; }
        });
        var processor = new Flow();
        LogRecordListener configured = record -> { };
        var registration = new ProcessorRegistration("p");
        var add = group.addNamedEventProcessor(() -> {
            resources.attachScoped(processor, new AuditCaptureIdentity(groupName, "p", registration.generation()),
                    configured, group::runOnAgentThread);
            return new NamedEventProcessor("p", processor);
        }, registration);
        var runner = new AgentRunner(new BusySpinIdleStrategy(), error -> { }, null, group);
        var groups = new ConcurrentHashMap<String, LifecycleManager.GroupRunner>();
        groups.put(groupName, new LifecycleManager.GroupRunner() {
            public AgentRunner getGroupRunner() { return runner; }
            public DynamicCompositeAgent getGroup() { return group; }
        });
        var manager = new LifecycleManager(null);
        Path evidenceDirectory = Path.of("target/migration-evidence");
        Files.createDirectories(evidenceDirectory);
        StopResult first = null;
        StopResult resumed = null;
        try {
            AgentRunner.startOnThread(runner);
            assertTrue(add.reply().get(5, TimeUnit.SECONDS).applied());
            assertTrue(group.auditStart("p", registration).reply().get(5, TimeUnit.SECONDS).applied());
            assertFalse(realQueue.get().isClosed());
            first = manager.stop(true, groups, new ConcurrentHashMap<>(), Map.of(), eventFlow, 0, 1000);
            StopResult expected = new StopResult(false, false, null, List.of(groupName), List.of());
            assertEquals(expected, first);
            assertSame(configured, processor.installed, "uninstall restored the listener before shutdown");
            assertEquals(0, group.captureResourcesHeld());
            assertEquals(1, group.captureResourceSlotsInUse());
            assertEquals(Map.of(1L, failure), group.captureCloseFailures());
            assertEquals(Map.of(1L, "physical close: " + failure), group.captureCleanupFailures());
            resumed = manager.stop(true, groups, new ConcurrentHashMap<>(), Map.of(), eventFlow, 0, 1000);
            assertEquals(expected, resumed);
            assertEquals(1, closeCalls.get(), "resumed stop must not repeat an uncertain physical close");
            assertFalse(realQueue.get().isClosed(), "the injected Chronicle close actually failed before closing its resource");
            var audit = ControlEvidence.parse(group.control().audit().exportText(), true);
            Path exported = evidenceDirectory.resolve("incomplete-shutdown-control.yaml");
            group.control().audit().export(exported);
            assertEquals(com.telamin.mongoose.control.ControlAuditExport.Completeness.COMPLETE,
                    com.telamin.mongoose.control.ControlAuditExport.verify(exported).completeness());
            assertFalse(com.telamin.mongoose.control.validation.ControlInvariants.check(audit).verified(),
                    "complete evidence of a failed close is not verified successful cleanup");
            var closes = audit.records().stream().filter(record -> record.type().equals("EffectOutcome")
                    && "CloseCapture".equals(record.str("effect")) && "failed".equals(record.str("outcome"))).toList();
            assertEquals(1, closes.size());
            assertEquals(1, closes.getFirst().num("resourceId"));
            assertEquals(failure, closes.getFirst().str("reason"));
            var pending = audit.records().stream().filter(record -> record.type().equals("CleanupPending")).toList();
            assertEquals(2, pending.size(), "one cleanup report per stop attempt");
            for (var record : pending) {
                assertEquals(groupName, record.str("group"));
                assertEquals(0, record.num("processorsLeft"));
                assertEquals(1, record.num("captureResourcesLeft"));
                assertEquals(1, record.num("failedPhysicalCloses"));
                assertEquals(0, record.num("failedCaptureDetaches"));
            }
            String helperFailure = "group " + groupName + " has 1 unresolved physical capture resources; cleanup failures={1=physical close: " + failure + "}";
            assertTrue(logRecords.stream().anyMatch(record -> record.getMessage().equals("mongoose-stop-" + groupName + " failed")
                    && record.getThrown() != null && record.getThrown().getMessage().equals(helperFailure)));
            assertTrue(logRecords.stream().anyMatch(record -> record.getMessage().equals("stop incomplete: processor group(s) ["
                    + groupName + "] did not close within the shutdown deadline; services left running for them: []")));
        } finally {
            group.control().audit().export(evidenceDirectory.resolve("incomplete-shutdown-control.yaml"));
            var formatter = new SimpleFormatter();
            var text = new StringBuilder();
            for (var record : logRecords) text.append(formatter.format(record));
            text.append("Observed first StopResult: ").append(first).append('\n');
            text.append("Observed resumed StopResult: ").append(resumed).append('\n');
            Files.writeString(evidenceDirectory.resolve("incomplete-shutdown.log"), text);
            lifecycleLog.removeHandler(observer);
            runner.close();
            // Only the test releases the real queue; production correctly remains incomplete without retrying it.
            if (realQueue.get() != null) realQueue.get().close();
        }
    }
}
