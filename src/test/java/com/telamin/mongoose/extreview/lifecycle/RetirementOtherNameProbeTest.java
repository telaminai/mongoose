package com.telamin.mongoose.extreview.lifecycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * External review probe: contract "A service is never stopped while a processor that was given it may still hold it".
 * The same physical instance is registered again under ANOTHER name while the withdrawal of its first name is still
 * awaiting confirmation. RemovedService.stop() only checks the OLD name for supersession.
 */
@Timeout(60)
class RetirementOtherNameProbeTest {

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

    @Test
    void retirementOfOldNameMustNotStopInstanceLiveUnderAnotherName() throws Exception {
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.setControlWaitMillis(300);
        MongooseServer s = new MongooseServer(cfg);
        s.init();
        s.start();
        Proc p = new Proc();
        Thread remover = null;
        try {
            s.addEventProcessor("p", "g", null, () -> p);
            Svc svc = new Svc();
            s.registerService(new Service<>(svc, Svc.class, "A"));
            assertTrue(p.registered.contains("A"));
            p.blockDeregister = true;
            remover = new Thread(() -> s.removeService("A"), "remover");
            remover.start();
            assertTrue(p.deregisterEntered.await(5, TimeUnit.SECONDS), "group is applying the withdrawal");
            // the same instance is admitted live again, under another name, while the withdrawal of "A" is unconfirmed
            s.registerService(new Service<>(svc, Svc.class, "B"));
            assertSame(svc, s.registeredServices().get("B").instance());
            p.blockDeregister = false;
            p.deregisterRelease.countDown();            // the group confirms the withdrawal of "A"
            remover.join(10_000);
            assertFalse(remover.isAlive(), "removeService returned");
            // let the register(B) broadcast land too
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!p.registered.contains("B") && System.nanoTime() < until) Thread.sleep(10);
            assertTrue(p.registered.contains("B"), "the processor was handed the instance under B");
            assertSame(svc, s.registeredServices().get("B").instance(), "still live under B");
            assertEquals(0, svc.stops.get(),
                    "the retirement of 'A' stopped an instance that is registered live under 'B' and held by a processor");
        } finally {
            p.deregisterRelease.countDown();
            if (remover != null) remover.join(5_000);
            s.stop();
        }
    }
}
