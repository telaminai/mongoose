package com.telamin.mongoose.service.servercontrol;

import com.telamin.fluxtion.runtime.DefaultEventProcessor;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.AgentThreads;
import com.telamin.mongoose.control.ControlAuditExport;
import com.telamin.mongoose.control.ControlAuditSink;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.AdminFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ControlAuditAdminExportTest {
    @TempDir Path directory;

    static class Commands implements AdminCommandRegistry {
        final Map<String, AdminFunction<String, String>> commands = new HashMap<>();
        @SuppressWarnings("unchecked")
        public <O, E> void registerCommand(String name, AdminFunction<O, E> command) {
            commands.put(name, (AdminFunction<String, String>) command);
        }
        public void processAdminCommandRequest(AdminCommandRequest request) { throw new UnsupportedOperationException(); }
        public List<String> commandList() { return List.copyOf(commands.keySet()); }
        String call(String name, String... args) {
            List<String> input = new ArrayList<>(); input.add(name); input.addAll(List.of(args));
            List<String> output = new ArrayList<>();
            commands.get(name).processAdminCommand(input, output::add, e -> output.add("ERROR " + e));
            return String.join("\n", output);
        }
    }

    private static DefaultEventProcessor processor() {
        var processor = new DefaultEventProcessor(new com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode());
        processor.init();
        return processor;
    }

    private Commands admin(MongooseServer server) {
        Commands commands = new Commands();
        MongooseServerAdmin admin = new MongooseServerAdmin();
        admin.admin(commands); admin.server(server); admin.start();
        return commands;
    }

    private String awaitFinished(Commands commands) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String status;
        do {
            status = commands.call("control.audit.export.status");
            if (!status.contains(" PENDING")) return status;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        fail("export did not finish: " + status);
        return status;
    }

    @Test void runningServerExportsItsOwnGraphAndVerifiableSnapshotWithoutStopping() throws Exception {
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        server.init(); server.start();
        try {
            server.addEventProcessor("quotes", "trading", null, ControlAuditAdminExportTest::processor);
            Commands commands = admin(server);
            Path target = directory.resolve("investigation");
            String response = commands.call("control.audit.export", "trading", target.toString());
            assertTrue(response.contains("job="), response);
            String jobId = response.substring(response.indexOf("job=") + 4, response.indexOf(" directory="));
            String status = awaitFinished(commands);
            assertTrue(status.contains("EXPORTED completeness=COMPLETE"), status);
            Path yaml = target.resolve("control-audit.yaml");
            assertTrue(Files.readString(yaml).contains("quotes"));
            assertEquals(ControlAuditExport.Completeness.COMPLETE, ControlAuditExport.verify(yaml).completeness());
            try (var graph = getClass().getClassLoader().getResourceAsStream(
                    "com/telamin/mongoose/control/generated/GroupControlProcessor.graphml")) {
                assertNotNull(graph);
                assertArrayEquals(graph.readAllBytes(), Files.readAllBytes(target.resolve("GroupControlProcessor.graphml")));
            }
            assertTrue(commands.call("control.audit.export.status", jobId).contains("EXPORTED"));
            assertTrue(commands.call("control.audit.export.status", "wrong-id").contains("unknown"));
            server.addEventProcessor("hedges", "trading", null, ControlAuditAdminExportTest::processor);
            assertTrue(server.registeredProcessors().get("trading").stream().anyMatch(p -> p.name().equals("hedges")));
            commands.call("control.audit.export", "trading", target.toString());
            assertTrue(awaitFinished(commands).contains("FAILED"), "existing files cannot be overwritten");
            assertEquals(ControlAuditExport.Completeness.COMPLETE, ControlAuditExport.verify(yaml).completeness());
        } finally { server.stop(); }
    }

    @Test void unknownGroupIsFailedAndCreatesNoDirectory() throws Exception {
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        Commands commands = admin(server);
        Path target = directory.resolve("unknown");
        commands.call("control.audit.export", "missing", target.toString());
        String status = awaitFinished(commands);
        assertTrue(status.contains("FAILED") && status.contains("unknown processor group: missing"), status);
        assertFalse(Files.exists(target));
    }

    @Test void synchronousExportRefusesAgentThreadBeforeAnyIo() {
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        AgentThreads.enter(server);
        try {
            assertThrows(IllegalStateException.class, () -> server.exportControlAudit("missing", directory.resolve("bad")));
        } finally { AgentThreads.exit(); }
        assertFalse(Files.exists(directory.resolve("bad")));
    }

    @Test void adminReturnsOnAgentThreadAndAdmitsOnlyOneWorkerWhileIoIsBlocked() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean workerIsAgent = new AtomicBoolean(true);
        MongooseServer server = new MongooseServer(new MongooseServerConfig()) {
            @Override public Path exportControlAudit(String group, Path dir) throws IOException {
                workerIsAgent.set(AgentThreads.isAgentThread()); entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test worker not released"); }
                catch (InterruptedException e) { throw new IOException(e); }
                ControlAuditSink sink = new ControlAuditSink(1);
                sink.fallback("first"); sink.fallback("second");
                return sink.export(dir.resolve("control-audit.yaml"));
            }
        };
        Commands commands = admin(server);
        try {
            AgentThreads.enter(server);
            try {
                assertTrue(commands.call("control.audit.export", "group", directory.toString()).contains("job="));
            } finally { AgentThreads.exit(); }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(workerIsAgent.get());
            assertTrue(commands.call("control.audit.export.status").contains("PENDING"));
            assertTrue(commands.call("control.audit.export", "group", directory.toString()).contains("already pending"));
        } finally { release.countDown(); }
        assertTrue(awaitFinished(commands).contains("EXPORTED completeness=INCOMPLETE"), "incomplete is never labelled complete");
    }
}
