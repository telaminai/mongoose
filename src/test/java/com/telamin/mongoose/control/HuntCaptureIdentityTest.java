package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.PerformanceMonitoringConfig;
import com.telamin.mongoose.service.audit.MongooseAuditCaptureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial hunt: capture identity. The design binds every capture to a (group, generation); the capture service
 * binds it to a processor NAME, which is only unique within a group.
 */
@Timeout(60)
class HuntCaptureIdentityTest {

    public static class Recording implements DataFlow {
        final List<LogRecordListener> listeners = new CopyOnWriteArrayList<>();
        @Override public void onEvent(Object event) { }
        @Override public void addEventFeed(EventFeed eventFeed) { }
        @Override public void setAuditLogProcessor(LogRecordListener listener) { listeners.add(listener); }
    }

    /**
     * FM: "p" in group g1 is recording. Adding a processor also named "p" to group g2 re-attaches the one name-keyed
     * sink to the new instance and, because the sink is recording, installs g1's capture listener on g2's processor:
     * two processors on two agent threads now feed one capture (one Chronicle appender), and the sink's instance is
     * g2's, so g1's later uninstall restores the listener on the wrong processor.
     */
    @Test
    void aSameNamedProcessorInAnotherGroupMustNotInheritTheCapture(@TempDir Path dir) throws Exception {
        MongooseServerConfig cfg = new MongooseServerConfig();
        Recording inG1 = new Recording();
        cfg.addProcessor("g1", inG1, "p");
        AuditCaptureConfig capture = new AuditCaptureConfig();
        capture.setEnabled(true);
        capture.setDirectory(dir.toString());
        PerformanceMonitoringConfig perf = new PerformanceMonitoringConfig();
        perf.setAuditCapture(capture);
        cfg.setPerformanceMonitoring(perf);
        MongooseServer server = MongooseServer.bootServer(cfg, rec -> { });
        try {
            MongooseAuditCaptureService svc = (MongooseAuditCaptureService)
                    server.registeredServices().get(MongooseAuditCaptureService.SERVICE_NAME).instance();
            svc.start("p");
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!svc.isRecording("p") && System.nanoTime() < until) Thread.sleep(5);
            assertTrue(svc.isRecording("p"), "precondition: p in g1 is recording");
            int installedOnG1 = inG1.listeners.size();
            assertTrue(installedOnG1 >= 2, "the server's listener, then the capture listener: " + installedOnG1);

            Recording inG2 = new Recording();
            server.addEventProcessor("p", "g2", null, () -> inG2);     // a different group: the name is free there
            Thread.sleep(300);                                          // the re-attach hand-off runs on g2's thread
            List<LogRecordListener> onG2 = new ArrayList<>(inG2.listeners);
            assertEquals(1, onG2.size(), "p in g2 asked for no capture: only the server's listener is installed, got " + onG2);
            assertEquals(installedOnG1, inG1.listeners.size(), "p in g1 keeps its own capture untouched");
        } finally {
            server.stop();
        }
    }
}
