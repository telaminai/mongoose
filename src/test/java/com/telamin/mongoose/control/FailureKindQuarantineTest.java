package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.EventLogControlEvent;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.control.event.FailureKind;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.generated.GroupControlProcessor;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review 77, F1: every rule node copies the effect's kind into its reply. A quarantined generated processor is
 * reported {@code QUARANTINED} by the service, capture and lifecycle rules alike; a plain capture uninstall failure is
 * {@code FAILED_EFFECT}. Each assertion classifies by kind, never reason text.
 */
@Timeout(30)
class FailureKindQuarantineTest {

    @AfterEach
    void clear() {
        ProcessorContext.removeCurrentProcessor();
        AgentThreads.exit();
    }

    static class Generated extends GroupControlProcessor {
        volatile boolean failAudit;

        Generated() {
            setAuditLogProcessor(record -> { if (failAudit) throw new IllegalStateException("injected audit failure"); });
            init();
            setAuditLogLevel(EventLogControlEvent.LogLevel.INFO);
        }
    }

    static final class Plain implements DataFlow, com.telamin.fluxtion.runtime.lifecycle.Lifecycle {
        @Override public void init() { }
        @Override public void addEventFeed(com.telamin.fluxtion.runtime.input.EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void tearDown() { }
    }

    private static ComposingEventProcessorAgent group(String name, ArrayDeque<Runnable> tasks, AtomicBoolean failUninstall) {
        var group = new ComposingEventProcessorAgent(name, new EventFlowManager(), null, new DeadWheelScheduler(),
                new ConcurrentHashMap<>());
        group.setCaptureIo(new CaptureIo() {
            public void open(Runnable task) { tasks.add(task); }
            public void close(Runnable task) { tasks.add(task); }
            public Cancellable schedule(Runnable task, long millis) { return () -> { }; }
        });
        group.setAuditCaptureResources(new com.telamin.mongoose.service.audit.AuditCaptureResources() {
            public Object openCapture(String n) { return new Object(); }
            public void installCapture(Object handle, String n, DataFlow flow) { }
            public void uninstallCapture(Object handle, String n, DataFlow flow) {
                if (failUninstall.get()) throw new IllegalStateException("injected uninstall failure");
            }
            public void closeCapture(Object handle) { }
        });
        return group;
    }

    private static void pump(ComposingEventProcessorAgent group, ArrayDeque<Runnable> tasks) throws Exception {
        for (int i = 0; i < 8; i++) {
            while (!tasks.isEmpty()) tasks.remove().run();
            group.doWork();
        }
    }

    @Test
    void serviceCaptureAndStopRepliesForAQuarantinedProcessorAreAllQuarantined() throws Exception {
        var tasks = new ArrayDeque<Runnable>();
        var group = group("q", tasks, new AtomicBoolean());
        Generated flow = new Generated();
        var registration = new ProcessorRegistration("generated");
        group.addNamedEventProcessor(() -> new NamedEventProcessor("generated", flow), registration);
        group.onStart();
        try {
            flow.failAudit = true;
            var service = group.broadcastService(new Service<>(new Object(), Object.class, "extra"), true, 1);
            group.doWork();
            ControlReply delivery = service.reply().join();
            assertEquals(ReplyOutcome.FAILED, delivery.outcome(), delivery.toString());
            assertEquals(FailureKind.QUARANTINED, delivery.kind(), "ServiceRules copies the delivery's kind: " + delivery);
            assertTrue(group.quarantinedProcessors().containsKey("generated"));

            var start = group.auditStart("generated", registration);
            pump(group, tasks);
            assertEquals(FailureKind.QUARANTINED, start.reply().join().kind(), "CaptureRules: " + start.reply().join());

            var stop = group.removeEventProcessorByName("generated", registration, null);
            pump(group, tasks);
            assertEquals(FailureKind.QUARANTINED, stop.reply().join().kind(), "LifecycleRules: " + stop.reply().join());
        } finally {
            group.onClose();
            pump(group, tasks);
        }
    }

    @Test
    void aFailedCaptureUninstallOfAPlainProcessorIsAFailedEffect() throws Exception {
        var tasks = new ArrayDeque<Runnable>();
        var failUninstall = new AtomicBoolean();
        var group = group("u", tasks, failUninstall);
        var registration = new ProcessorRegistration("p");
        group.addNamedEventProcessor(() -> new NamedEventProcessor("p", new Plain()), registration);
        group.onStart();
        try {
            var start = group.auditStart("p", registration);
            pump(group, tasks);
            assertTrue(start.reply().join().applied());
            failUninstall.set(true);
            var stop = group.auditStop("p", registration);
            pump(group, tasks);
            ControlReply reply = stop.reply().join();
            assertEquals(ReplyOutcome.FAILED, reply.outcome(), reply.toString());
            assertEquals(FailureKind.FAILED_EFFECT, reply.kind(), reply.toString());
            failUninstall.set(false);
        } finally {
            group.onClose();
            pump(group, tasks);
        }
    }
}
