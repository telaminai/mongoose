package com.telamin.mongoose.extreview2;

import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Second review, Q1/Q3 on finding 6 / gate E3: the original probe asserts zero stops immediately after the register(B)
 * broadcast lands, but the retirement stop of "A" runs asynchronously on the reply executor (MongooseServer.java:880-886),
 * so the probe can pass on the defect. This variant waits for the asynchronous stop to settle before judging.
 */
@Timeout(60)
class RetirementOtherNameSettledTest {

    static class Proc implements DataFlow, Lifecycle {
        final CountDownLatch deregisterEntered = new CountDownLatch(1);
        final CountDownLatch deregisterRelease = new CountDownLatch(1);
        volatile boolean blockDeregister;
        final java.util.List<String> registered = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void tearDown() { }
        @Override public void registerService(Service<?> s) { registered.add(s.serviceName()); }
        @Override public void deRegisterService(Service<?> s) {
            if (!blockDeregister) return;
            deregisterEntered.countDown();
            try { deregisterRelease.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    static final class Svc implements Lifecycle {
        final AtomicInteger stops = new AtomicInteger();
        @Override public void init() { }
        @Override public void start() { }
        @Override public void stop() { stops.incrementAndGet(); }
        @Override public void tearDown() { }
    }


    @RepeatedTest(5)
    void retirementOfOldNameMustNotStopInstanceLiveUnderAnotherNameAfterSettling() throws Exception {
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.setControlWaitMillis(300);
        MongooseServer s = new MongooseServer(cfg);
        s.init();
        s.start();
        Proc p = new Proc();
        Svc svc = new Svc();
        Thread remover = null;
        try {
            s.addEventProcessor("p", "g", null, () -> p);
            s.registerService(new Service<>(svc, Svc.class, "A"));
            p.blockDeregister = true;
            remover = new Thread(() -> s.removeService("A"), "remover");
            remover.start();
            assertTrue(p.deregisterEntered.await(5, TimeUnit.SECONDS));
            s.registerService(new Service<>(svc, Svc.class, "B"));
            p.blockDeregister = false;
            p.deregisterRelease.countDown();
            remover.join(10_000);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (svc.stops.get() == 0 && System.nanoTime() < until) Thread.sleep(10);
            assertSame(svc, s.registeredServices().get("B").instance(), "still live under B");
            assertEquals(0, svc.stops.get(), "retirement of 'A' stopped the instance live under 'B'");
        } finally {
            p.deregisterRelease.countDown();
            if (remover != null) remover.join(5_000);
            s.stop();
        }
    }
}
