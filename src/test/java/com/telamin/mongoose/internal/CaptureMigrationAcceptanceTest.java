package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.replay.generated.AlarmNodes;
import com.telamin.mongoose.replay.generated.AlarmProcessor;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.audit.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real YAML boot and generated processors; no hand-written replacement for their event dispatcher. */
@Timeout(60)
public class CaptureMigrationAcceptanceTest {
    public static class TrackedProcessor extends AlarmProcessor {
        volatile LogRecordListener listener;
        final AtomicInteger readings = new AtomicInteger();
        @Override public void setAuditLogProcessor(LogRecordListener listener) {
            super.setAuditLogProcessor(listener);
            this.listener = listener;
        }
        @Override public void onEvent(Object event) {
            super.onEvent(event);
            if (event instanceof AlarmNodes.Reading) readings.incrementAndGet();
        }
    }
    public static class FailedStart extends TrackedProcessor {
        @Override public void start() { throw new IllegalStateException("migration injected boot failure"); }
    }

    static String yaml(Path dir, boolean fail) {
        String processor = fail ? FailedStart.class.getName() : TrackedProcessor.class.getName();
        String base = """
                performanceMonitoring:
                  enabled: true
                  auditCapture:
                    enabled: true
                    directory: '%s'
                    autoStart: [p, unique]
                services:
                  - name: admin
                    serviceClass: com.telamin.mongoose.service.admin.AdminCommandRegistry
                    service: !!com.telamin.mongoose.service.admin.impl.AdminCommandProcessor {}
                  - name: serverAdmin
                    service: !!com.telamin.mongoose.service.servercontrol.MongooseServerAdmin {}
                eventFeeds:
                  - name: readings
                    instance: !!com.telamin.mongoose.connector.memory.InMemoryEventSource {}
                    broadcast: true
                    agentName: readings-agent
                eventHandlers:
                  - agentName: g1
                    eventHandlers:
                      p:
                        eventHandler: !!%s {}
                        logLevel: INFO
                """.formatted(dir.toString().replace("'", "''"), processor);
        if (fail) return base;
        return base + """
                      unique:
                        eventHandler: !!%s {}
                        logLevel: INFO
                  - agentName: g2
                    eventHandlers:
                      p:
                        eventHandler: !!%s {}
                        logLevel: INFO
                """.formatted(processor, processor);
    }

    @Test void yamlCaptureSupportCycleAndShutdown(@TempDir Path dir) throws Exception {
        List<String> logs = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            public void publish(LogRecord r) { logs.add(r.getLoggerName() + ": " + r.getMessage()); }
            public void flush() { }
            public void close() { }
        };
        Logger logger = Logger.getLogger("");
        logger.addHandler(handler);
        LogRecordListener configured = record -> { };
        MongooseServer server = MongooseServer.bootServer(new StringReader(yaml(dir, false)), configured);
        try {
            var capture = (MongooseAuditCaptureService) server.registeredServices()
                    .get(MongooseAuditCaptureService.SERVICE_NAME).instance();
            var index = (MongooseAuditIntrospectionService) server.registeredServices()
                    .get(MongooseAuditIntrospectionService.SERVICE_NAME).instance();
            var admin = (AdminCommandRegistry) server.registeredServices().get("admin").instance();
            // Auto-start is asynchronous: boot establishes lifecycle readiness, not capture I/O completion.
            await(() -> index.currentSinks().size() == 3);
            List<TrackedProcessor> processors = server.registeredProcessors().values().stream()
                    .flatMap(Collection::stream).map(p -> (TrackedProcessor) p.eventProcessor()).toList();
            assertEquals(3, processors.size());
            @SuppressWarnings("unchecked") var feed = (InMemoryEventSource<Object>) server.registeredServices()
                    .get("readings").instance();
            feed.offer(new AlarmNodes.Reading(12));
            await(() -> processors.stream().allMatch(p -> p.readings.get() == 1));
            for (var p : processors) assertNotSame(configured, p.listener);
            assertTrue(capture.isRecording("unique"));
            assertEquals(index.currentSink("g1/unique").id(), index.currentSink("unique").id());
            assertTrue(command(admin, "audit.status", "unique").contains("RECORDING"));
            assertTrue(command(admin, "audit.stop", "unique").contains("STOPPED"));
            assertFalse(capture.isRecording("unique"));
            assertTrue(command(admin, "audit.start", "unique").contains("RECORDING"));
            assertTrue(capture.isRecording("unique"));
            String ambiguity = command(admin, "audit.start", "p");
            assertTrue(ambiguity.contains("g1/p") && ambiguity.contains("g2/p"), ambiguity);

            TrackedProcessor first = (TrackedProcessor) server.registeredProcessors().get("g1").stream()
                    .filter(p -> p.name().equals("p")).findFirst().orElseThrow().eventProcessor();
            String oldId = index.currentSink("g1/p").id();
            assertTrue(command(admin, "audit.stop", "g1/p").contains("STOPPED"));
            assertSame(configured, first.listener);
            assertTrue(capture.isRecording("g2/p"));
            assertTrue(command(admin, "audit.start", "g1/p").contains("RECORDING"));
            assertNotEquals(oldId, index.currentSink("g1/p").id());
            List<AuditSinkHandle> listing = index.listAvailable();
            assertEquals(5, listing.size());
            assertEquals(5, listing.stream().map(AuditSinkHandle::id).distinct().count());
            assertEquals(3, listing.stream().filter(AuditSinkHandle::isLive).count());
            for (AuditSinkHandle h : listing) {
                assertEquals(h.id(), h.path().getFileName().toString());
                assertTrue(Files.isRegularFile(h.path().resolve(".mongoose-capture.properties")));
            }
            String status = command(admin, "audit.status");
            String all = command(admin, "audit.list");
            assertTrue(status.contains("g1/p") && status.contains("g2/p"), status);
            for (AuditSinkHandle h : listing) assertTrue(all.contains(h.path().toString()), all);
            StopResult stop = server.shutdown();
            assertEquals(StopResult.completed(), stop);
            for (var p : processors) assertSame(configured, p.listener, "shutdown restores the exact listener");
            assertEquals(0, index.listAvailable().stream().filter(AuditSinkHandle::isLive).count());
            List<String> phases = List.of("stopping feeds", "draining queued events, at most ",
                    "stopping event processor agents", "stopping agent hosted services", "stopping registered services");
            int previous = -1;
            for (String phase : phases) {
                int found = -1;
                for (int i = previous + 1; i < logs.size(); i++) if (logs.get(i).contains(phase)) { found = i; break; }
                assertTrue(found > previous, "missing/out-of-order runbook phase " + phase + " in " + logs);
                previous = found;
            }
            Files.createDirectories(Path.of("target/migration-evidence"));
            Files.writeString(Path.of("target/migration-evidence/complete-shutdown.log"),
                    String.join("\n", logs) + "\nStopResult: " + stop + "\n");
        } finally {
            server.stop();
            logger.removeHandler(handler);
        }
    }

    @Test void failedBootNeverOpensItsAutoStartCapture(@TempDir Path dir) throws Exception {
        // bootServer deliberately installs the process-exiting error handler: isolate that real contract.
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> a.startsWith("--add-opens")).toList());
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), FailedBootMain.class.getName(), dir.toString()));
        Path log = dir.resolve("failed-boot.log");
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "failed boot must exit");
            assertNotEquals(0, child.exitValue());
            assertTrue(Files.readString(log).contains("migration injected boot failure"), Files.readString(log));
        } finally { if (child.isAlive()) child.destroyForcibly(); }
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("capture-")));
        }
    }

    public static class FailedBootMain {
        public static void main(String[] args) {
            MongooseServer.bootServer(new StringReader(yaml(Path.of(args[0]), true)), r -> { });
            throw new AssertionError("failed processor must not produce a successful boot");
        }
    }

    static String command(AdminCommandRegistry admin, String name, String... args) {
        List<String> replies = new CopyOnWriteArrayList<>();
        AdminCommandRequest request = new AdminCommandRequest();
        request.setCommand(name); request.setArguments(List.of(args));
        request.setOutput(o -> replies.add(String.valueOf(o)));
        request.setErrOutput(e -> replies.add("ERROR: " + e));
        admin.processAdminCommandRequest(request);
        return String.join("\n", replies);
    }
    static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "condition did not settle in 5 seconds");
    }
}
