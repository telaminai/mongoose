package com.telamin.mongoose.review77ext;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.*;
import com.telamin.mongoose.dutycycle.ServiceAgent;
import com.telamin.mongoose.service.health.*;
import com.telamin.fluxtion.runtime.service.Service;
import org.agrona.concurrent.Agent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ServiceHealthObservationFollowupTest {
    @Test void deadWorkerIsDownWithoutFirstQueryingSeparateAgentGroupHealth() throws Exception {
        var cfg=new MongooseServerConfig(); cfg.setShutdownTimeoutMillis(500);
        var perf=new PerformanceMonitoringConfig(); perf.setEnabled(true); cfg.setPerformanceMonitoring(perf);
        var server=new MongooseServer(cfg); server.setDefaultErrorHandler(t -> {});
        var fail=new AtomicBoolean(); var thread=new AtomicReference<Thread>();
        var started=new CountDownLatch(1);
        Agent worker=new Agent() {
            public int doWork() {
                thread.set(Thread.currentThread()); started.countDown();
                if(fail.get()) throw new AssertionError("fatal worker failure");
                return 0;
            }
            public String roleName() { return "worker"; }
        };
        server.registerWorkerService(new ServiceAgent<>("worker-group",null,new Service<>((Runnable)()->{},Runnable.class,"svc"),worker));
        server.init(); server.start();
        try {
            assertTrue(started.await(2,TimeUnit.SECONDS)); fail.set(true); thread.get().join(2000);
            assertFalse(thread.get().isAlive());
            var health=(MongooseHealthService)server.registeredServices().get(MongooseHealthService.SERVICE_NAME).instance();
            assertEquals(HealthStatus.Verdict.DOWN,health.statusOfCheck("svc","up").verdict(),
                    "service-only monitoring must observe its dead worker without evaluating a different health check first");
        } finally { server.stop(); }
    }
}

