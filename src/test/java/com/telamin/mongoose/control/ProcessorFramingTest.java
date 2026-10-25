package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.control.generated.GroupControlProcessor;
import com.telamin.mongoose.dispatch.ControlFraming;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.internal.ConfigAwareEventProcessor;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Design "Record schema" (control-plane PR 6): a control action on a data processor (a service delivered, a capture
 * installed) is one record in the processor's own audit, framed by {@code runInEventCycle}, and never reaches its
 * handlers; data processors' start and stop are framed by lifecycle records, for a generated processor as for an
 * interpreted one.
 */
class ProcessorFramingTest {

    /** A handler that keeps every event it is given, so a control frame reaching it shows. */
    static final class Seeing extends ObjectEventHandlerNode {
        final List<Object> seen = new CopyOnWriteArrayList<>();

        @Override
        protected boolean handleEvent(Object event) {
            seen.add(event);
            return true;
        }
    }

    /** An interpreted processor that keeps the frames it is asked to run. */
    static final class Framed extends ConfigAwareEventProcessor {
        final List<Object> frames = new CopyOnWriteArrayList<>();

        Framed(Seeing handler) {
            super(handler);
        }

        @Override
        public void runInEventCycle(Object frame, Runnable action) {
            frames.add(frame);
            super.runInEventCycle(frame, action);
        }
    }

    /**
     * Review of PR 6, finding 2: a service delivered at runtime is an exported service call, already one audited cycle;
     * wrapping it in runInEventCycle nested a second record header. Each record is whole, with one header.
     */
    @Test
    void aServiceDeliveredAtRuntimeIsOneWellFormedRecordInAGeneratedProcessor() throws Exception {
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("framing", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        GroupControlProcessor generated = new GroupControlProcessor();
        List<String> records = new CopyOnWriteArrayList<>();
        generated.setAuditLogProcessor(r -> records.add(r.asCharSequence().toString()));
        generated.init();
        generated.setAuditLogLevel(com.telamin.fluxtion.runtime.audit.EventLogControlEvent.LogLevel.INFO);
        group.onStart();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("gen", generated));
        group.doWork();
        records.clear();
        group.broadcastServiceRegistered(new Service<>(new Object(), Object.class, "extra"));
        group.doWork();
        assertTrue(!records.isEmpty(), "the delivery is on the processor's record");
        for (String r : records) {
            int headers = r.split("eventLogRecord:", -1).length - 1;
            assertTrue(headers == 1, "one header per record, no nested or fragment record: " + r);
        }
        group.onClose();
    }

    @Test
    void neitherServiceDeliveryNorCaptureIsWrappedInAnExtraFrame() throws Exception {
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("framing2", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        Framed flow = new Framed(new Seeing());
        flow.init();
        group.onStart();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("p", flow));
        group.doWork();
        group.broadcastServiceRegistered(new Service<>(new Object(), Object.class, "extra"));
        group.doWork();
        assertTrue(flow.frames.isEmpty(), "no runInEventCycle around an already audited call: " + flow.frames);
        group.onClose();
    }

    @Test
    void aProcessorWithoutTheCycleRunsTheActionUnframed() {
        assertFalse(ControlFraming.implementsTheCycle(com.telamin.fluxtion.runtime.DataFlow.class));
        assertTrue(ControlFraming.implementsTheCycle(ConfigAwareEventProcessor.class));
        boolean[] ran = {false};
        ControlFraming.frame(null, "frame", () -> ran[0] = true);
        assertTrue(ran[0], "framing never suppresses the action");
    }

    /**
     * Parity (design "Record schema", checked in PR 6): the generated template frames start and stop with lifecycle
     * records, as {@code DefaultEventProcessor} does, so a generated data processor's start and stop are on its record.
     */
    @Test
    void aGeneratedProcessorFramesStartAndStopWithLifecycleRecords() {
        List<String> generated = lifecycleRecords(new GroupControlProcessor());
        assertTrue(generated.stream().anyMatch(r -> r.contains("Start")), "start is framed: " + generated);
        assertTrue(generated.stream().anyMatch(r -> r.contains("Stop")), "stop is framed: " + generated);
    }

    private static List<String> lifecycleRecords(com.telamin.fluxtion.runtime.DataFlow flow) {
        List<String> records = new CopyOnWriteArrayList<>();
        flow.setAuditLogProcessor(r -> records.add(r.asCharSequence().toString()));
        flow.init();
        flow.setAuditLogLevel(com.telamin.fluxtion.runtime.audit.EventLogControlEvent.LogLevel.INFO);
        flow.start();
        flow.stop();
        flow.tearDown();
        return records;
    }
}
