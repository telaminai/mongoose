package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.DefaultEventProcessor;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.PerformanceMonitoringConfig;
import com.telamin.mongoose.internal.ChronicleAuditCaptureService;
import com.telamin.mongoose.internal.NoOpCountersService;
import com.telamin.mongoose.service.audit.MongooseAuditCaptureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mongoose#46 (spec-replay-recording R7): audit.start / audit.stop run on the admin transport's thread, and used to
 * change the processor's audit sink from it, so onEvent ran on two threads. The change now runs on the processor's own
 * agent thread, and start/stop return once it has.
 */
class AuditSinkOnAgentThreadTest {

    static AuditCaptureConfig capture(Path dir) {
        AuditCaptureConfig c = new AuditCaptureConfig();
        c.setEnabled(true);
        c.setDirectory(dir.toString());
        return c;
    }

    @Test
    void theCaptureServiceChangesTheSinkOnTheAgentThread(@TempDir Path dir) throws Exception {
        List<String> installedOn = new CopyOnWriteArrayList<>();
        DataFlow flow = (DataFlow) Proxy.newProxyInstance(DataFlow.class.getClassLoader(), new Class<?>[]{DataFlow.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAuditLogProcessor" -> {
                        installedOn.add(Thread.currentThread().getName());
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "DEMO-flow";
                    default -> null;
                });
        ExecutorService agent = Executors.newSingleThreadExecutor(r -> new Thread(r, "DEMO-agent"));
        try {
            ChronicleAuditCaptureService svc = new ChronicleAuditCaptureService(capture(dir), NoOpCountersService.INSTANCE);
            svc.attach(flow, "p", r -> { }, agent::execute);
            svc.start("p");                             // from the test thread, as the admin transport would
            assertEquals(List.of("DEMO-agent"), installedOn, "installed on the agent thread, and applied before start returned");
            svc.stop("p");
            assertEquals(List.of("DEMO-agent", "DEMO-agent"), installedOn, "restored on the agent thread too");
        } finally {
            agent.shutdownNow();
        }
    }

    /** A processor that notes the thread each audit sink is installed on. */
    public static class ThreadNotingProcessor extends DefaultEventProcessor {
        final List<String> installedOn = new CopyOnWriteArrayList<>();

        public ThreadNotingProcessor() {
            super(new ObjectEventHandlerNode());
        }

        @Override
        public void setAuditLogProcessor(LogRecordListener listener) {
            installedOn.add(Thread.currentThread().getName());
            super.setAuditLogProcessor(listener);
        }
    }

    @Test
    void theServerHandsTheCaptureServiceTheGroupsThread(@TempDir Path dir) throws Exception {
        ThreadNotingProcessor processor = new ThreadNotingProcessor();
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("processor-agent")
                        .put("quotes", EventProcessorConfig.builder().handler(processor).build()).build())
                .build();
        PerformanceMonitoringConfig perf = new PerformanceMonitoringConfig();
        perf.setAuditCapture(capture(dir));
        config.setPerformanceMonitoring(perf);
        MongooseServer server = MongooseServer.bootServer(config, rec -> { });
        try {
            Thread.sleep(200);
            MongooseAuditCaptureService capture = (MongooseAuditCaptureService)
                    server.registeredServices().get(MongooseAuditCaptureService.SERVICE_NAME).instance();
            int before = processor.installedOn.size();
            String admin = Thread.currentThread().getName();
            capture.start("quotes");                    // the admin transport's call, from this thread
            capture.stop("quotes");
            List<String> threads = processor.installedOn.subList(before, processor.installedOn.size());
            assertEquals(2, threads.size(), processor.installedOn.toString());
            assertFalse(threads.contains(admin), "never on the caller's thread: " + threads);
            assertTrue(threads.stream().allMatch(t -> t.contains("processor-agent")), "on the group's thread: " + threads);
        } finally {
            server.stop();
        }
    }
}
