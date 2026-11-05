package com.telamin.mongoose.review77ext;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.MongooseServerConfig.FatalErrorPolicy;
import com.telamin.mongoose.dutycycle.GlobalErrorHandler;
import com.telamin.mongoose.service.health.HealthStatus;
import com.telamin.mongoose.service.health.MongooseHealthService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * External re-verification B (written from the reviewer's description): after a real fatal worker error, the worker
 * service's own {@code up} check, read alone and without evaluating any group check, is not UP.
 */
public class ServiceHealthObservationFollowupOwnTest {

    @Test
    void aDeadWorkersServiceIsNotUpWhenOnlyItsOwnCheckIsRead() throws Exception {
        var config = new MongooseServerConfig();
        var perf = new com.telamin.mongoose.config.PerformanceMonitoringConfig();
        perf.setEnabled(true);
        config.setPerformanceMonitoring(perf);
        MongooseServer server = new MongooseServer(config);
        server.setDefaultErrorHandler(new GlobalErrorHandler(FatalErrorPolicy.CONTAIN, status -> { }));
        AtomicBoolean fail = new AtomicBoolean();
        AtomicReference<Thread> thread = new AtomicReference<>();
        org.agrona.concurrent.Agent delegate = new org.agrona.concurrent.Agent() {
            public int doWork() {
                thread.set(Thread.currentThread());
                if (fail.get()) throw new AssertionError("injected fatal worker error");
                return 0;
            }
            public String roleName() { return "worker-delegate"; }
        };
        Runnable instance = () -> { };
        server.registerWorkerService(new com.telamin.mongoose.dutycycle.ServiceAgent<>("worker-agent", null,
                new com.telamin.fluxtion.runtime.service.Service<>(instance, Runnable.class, "worker"), delegate));
        server.init();
        server.start();
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (thread.get() == null && System.nanoTime() < until) Thread.sleep(5);
            fail.set(true);
            thread.get().join(5_000);
            assertTrue(!thread.get().isAlive(), "the worker's thread ended");
            MongooseHealthService health = (MongooseHealthService) server.registeredServices()
                    .get(MongooseHealthService.SERVICE_NAME).instance();
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);     // health evaluations are cached for 1 s
            while (health.statusOfCheck("worker", "up").verdict() == HealthStatus.Verdict.UP && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertNotEquals(HealthStatus.Verdict.UP, health.statusOfCheck("worker", "up").verdict(),
                    "the dead worker's own up check, read alone: " + health.statusOfCheck("worker", "up"));
        } finally {
            server.stop();
        }
    }
}
