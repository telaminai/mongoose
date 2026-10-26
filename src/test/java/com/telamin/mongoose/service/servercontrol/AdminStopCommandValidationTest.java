package com.telamin.mongoose.service.servercontrol;

import com.telamin.fluxtion.runtime.DefaultEventProcessor;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.ControlReply;
import com.telamin.mongoose.control.event.FailureKind;
import com.telamin.mongoose.control.event.ReplyOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admin-web validation against rc1 (issue #77): {@code server.processors.stop} validates its argument instead of
 * failing with an index error, and an unknown group or name is answered REFUSED with a kind, never "pending".
 */
@Timeout(20)
class AdminStopCommandValidationTest {

    private static DefaultEventProcessor processor() {
        var processor = new DefaultEventProcessor(new com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode());
        processor.init();
        return processor;
    }

    @Test
    void malformedAndUnknownStopsAreRefusedNeverPending() {
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        server.init();
        server.start();
        try {
            server.addEventProcessor("quotes", "trading", null, AdminStopCommandValidationTest::processor);
            var commands = new ControlAuditAdminExportTest.Commands();
            MongooseServerAdmin admin = new MongooseServerAdmin();
            admin.admin(commands);
            admin.server(server);
            admin.start();

            for (String[] bad : new String[][]{{}, {"trading"}, {"trading/"}, {"/quotes"}, {"a/b/c"}, {"trading/quotes", "x"}}) {
                String out = commands.call("server.processors.stop", bad);
                assertTrue(out.startsWith("ERROR stop REFUSED (REFUSED_INVALID): usage:"), String.join(" ", bad) + " -> " + out);
            }
            String unknownName = commands.call("server.processors.stop", "trading/nobody");
            assertTrue(unknownName.contains("stop REFUSED (REFUSED_STATE): no such processor"), unknownName);
            assertFalse(unknownName.contains("pending"), unknownName);
            String unknownGroup = commands.call("server.processors.stop", "nogroup/quotes");
            assertTrue(unknownGroup.contains("stop REFUSED (REFUSED_STATE): no such group"), unknownGroup);

            ControlReply direct = server.stopProcessor("trading", "nobody", 1_000);
            assertEquals(ReplyOutcome.REFUSED, direct.outcome());
            assertEquals(FailureKind.REFUSED_STATE, direct.kind());

            // issue #77 M4: the group status as JSON for the admin web console
            String json = commands.call("control.group.status", "trading", "--json");
            assertTrue(json.startsWith("{\"group\":\"trading\",\"state\":\"RUNNING\",") && json.endsWith("}"), json);
            assertTrue(json.contains("\"quarantinedProcessors\":{}") && json.contains("\"controlAuditDegraded\":false")
                    && json.contains("\"captureWriteFailures\":0"), json);
            assertTrue(commands.call("control.group.status", "trading", "--yaml").startsWith("ERROR usage:"));

            String stopped = commands.call("server.processors.stop", "trading/quotes");
            assertTrue(stopped.contains("stop APPLIED (NONE)"), stopped);
        } finally {
            server.stop();
        }
    }
}
