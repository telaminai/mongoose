package com.telamin.mongoose.replay.generated;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.EventLogControlEvent;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ServiceConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.telamin.mongoose.replay.generated.GeneratedAdminAuditTest.await;
import static com.telamin.mongoose.replay.generated.GeneratedAdminAuditTest.command;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lambda admin command on a processor GENERATED for fluxtion runtime 1.1.0 (CycleAlarmProcessor, design-doc/admin-gen):
 * it runs through runInEventCycle as its own cycle, so its audit record holds its node's lines, and an event it raises
 * is queued and follows as a record of its own. The witness is the same command on AlarmProcessor, generated before
 * 1.1.0, where the invoker brackets it and the raised event runs INSIDE the command's record.
 */
class GeneratedCycleAdminAuditTest {

    /** The audit records of: a reading that raises the alarm, then the refresh command, which raises a reading. */
    static List<String> run(DataFlow processor) throws Exception {
        List<String> records = new CopyOnWriteArrayList<>();
        InMemoryEventSource<Object> readings = new InMemoryEventSource<>();
        readings.setName("readings");
        AdminCommandProcessor admin = new AdminCommandProcessor();
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("processor-agent")
                        .put("alarms", EventProcessorConfig.builder().handler(processor)
                                .logLevel(EventLogControlEvent.LogLevel.INFO).build()).build())
                .addEventFeed(EventFeedConfig.builder().instance(readings).name("readings").broadcast(true)
                        .agent("readings-agent", new BusySpinIdleStrategy()).build())
                .addService(new ServiceConfig<>(admin, AdminCommandRegistry.class, "adminService"))
                .build();
        MongooseServer server = MongooseServer.bootServer(config, r -> records.add(String.valueOf(r.asCharSequence())));
        try {
            Thread.sleep(300);
            int start = records.size();
            readings.offer(new AlarmNodes.Reading(12));      await(records, start + 1);
            assertEquals(List.of("refreshed"), command(admin, "alarm.refresh"));
            await(records, start + 3);
            records.subList(0, start).clear();
        } finally {
            server.stop();
        }
        return records;
    }

    @Test
    void onA1_1_0Processor_aLambdaCommandIsItsOwnCycle_andTheEventItRaisesFollows() throws Exception {
        List<String> records = run(new CycleAlarmProcessor());
        Files.writeString(Path.of("target/generated-cycle-admin-audit.yaml"), String.join("\n---\n", records));
        String command = records.stream().filter(r -> r.contains("refreshRequested")).findFirst().orElseThrow();
        assertTrue(command.contains("AdminCommandEvent[command=alarm.refresh"), "the record names the command: " + command);
        assertFalse(command.contains("value: 5.0"), "the raised reading is not inside the command's record: " + command);
        assertEquals(1, command.split("eventLogRecord:", -1).length - 1, "one record, well formed: " + command);
        int at = records.indexOf(command);
        assertTrue(at + 1 < records.size() && records.get(at + 1).contains("value: 5.0"),
                "the raised reading follows, as its own record: " + records);
    }

    @Test
    void witness_onAPre1_1_0Processor_theRaisedEventRunsInsideTheCommandsRecord() throws Exception {
        List<String> records = run(new AlarmProcessor());
        String command = records.stream().filter(r -> r.contains("refreshRequested")).findFirst().orElseThrow();
        assertTrue(command.contains("value: 5.0"), "the bracket dispatches it at once, inside the command: " + command);
    }

    @Test
    void theGeneratedSourceIsPublishable() throws Exception {
        String source = Files.readString(Path.of("src/test/java/com/telamin/mongoose/replay/generated/CycleAlarmProcessor.java"));
        assertTrue(source.startsWith("package com.telamin.mongoose.replay.generated;"), "no header before the package");
        assertFalse(source.contains("Copyright") || source.contains("All Rights Reserved"), "no generator copyright header");
        assertTrue(source.contains("public void runInEventCycle(Object auditEvent, Runnable action)"), "a 1.1.0 processor");
    }
}
