package com.telamin.mongoose.recovery;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.EventLogControlEvent;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.recovery.generated.RecoveryClientProcessor;
import com.telamin.mongoose.service.error.ErrorEvent;
import com.telamin.mongoose.service.error.ErrorListener;
import com.telamin.mongoose.service.error.ErrorReporting;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CompiledClientRecoveryTest {
    private static RecoveryClientProcessor started() {
        var graph = new RecoveryClientProcessor();
        graph.init(); graph.start();
        graph.setAuditLogLevel(EventLogControlEvent.LogLevel.INFO);
        return graph;
    }

    @Test void failingNodeClosesOneRecordAndNextEventHasItsOwnRecord() throws Exception {
        var graph = started();
        List<String> records = new ArrayList<>();
        graph.setAuditLogProcessor(record -> records.add(record.asCharSequence().toString()));
        assertEquals(1, records.size(), "installing the listener is itself an audited configuration event");
        assertTrue(records.getFirst().contains("event: EventLogControlEvent"));
        records.clear();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> graph.onEvent(new RecoveryProbeNode.Input(1)));
        assertEquals("client failure after effect 1", failure.getMessage());
        assertEquals(1, records.size(), () -> "one record for the failed node cycle: " + records);
        graph.onEvent(new RecoveryProbeNode.Input(2));
        assertEquals(List.of(1, 2), graph.handler.effects, "the stale queued 99 is discarded, the earlier effect remains");
        assertEquals(2, records.size(), "exactly one record for the next cycle too");
        var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        for (int i = 0; i < records.size(); i++) {
            String record = records.get(i);
            assertEquals(1, record.split("eventLogRecord:", -1).length - 1, record);
            Object parsed = yaml.load(record);
            assertInstanceOf(Map.class, parsed);
            assertTrue(((Map<?, ?>) parsed).containsKey("eventLogRecord"), record);
            assertTrue(record.contains("inputId: " + (i + 1)), record);
            assertFalse(record.contains("inputId: 99"), record);
        }
        Path out = Path.of(System.getProperty("mongoose.recovery.auditDir", "target/recovery-audit"));
        Files.createDirectories(out);
        Files.writeString(out.resolve("failed-then-next.yaml"), "---\n" + String.join("\n---\n", records) + "\n---\n");
        graph.setAuditLogProcessor(record -> { });
        graph.stop(); graph.tearDown();
    }

    @Test void hostReportsOneFailureAndKeepsHealthyGraphRunningWithoutRetry() {
        var graph = started();
        var queue = new OneToOneConcurrentArrayQueue<Object>(16);
        var reader = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(), "compiled-client-failure");
        var healthy = new AtomicInteger();
        List<ErrorEvent> errors = new CopyOnWriteArrayList<>();
        ErrorListener listener = errors::add;
        ErrorReporting.getReporter().addListener(listener);
        reader.registerProcessor(graph);
        reader.registerProcessor((DataFlow) event -> healthy.incrementAndGet());
        try {
            queue.offer(new RecoveryProbeNode.Input(1)); queue.offer(new RecoveryProbeNode.Input(2));
            assertEquals(2, reader.doWork());
            assertEquals(List.of(1), graph.handler.effects, "the host must not retry a partially applied input");
            assertEquals(2, healthy.get());
            assertEquals(1, reader.listenerCount());
            var mine = errors.stream().filter(e -> String.valueOf(e.getSource())
                    .equals("EventQueueToEventProcessorAgent:compiled-client-failure")).toList();
            assertEquals(1, mine.size(), mine.toString());
            assertEquals(ErrorEvent.Severity.ERROR, mine.getFirst().getSeverity());
            Throwable original = mine.getFirst().getError();
            while (original.getCause() != null) original = original.getCause();
            assertInstanceOf(IllegalStateException.class, original);
            assertEquals("client failure after effect 1", original.getMessage());
            assertNull(ProcessorContext.currentProcessor());
        } finally {
            reader.onClose();
            ErrorReporting.getReporter().removeListener(listener);
            ProcessorContext.removeCurrentProcessor();
        }
    }
}
