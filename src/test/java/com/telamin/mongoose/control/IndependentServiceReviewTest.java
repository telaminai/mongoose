package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class IndependentServiceReviewTest {
    static class Proc implements DataFlow, Lifecycle {
        final AtomicInteger stops = new AtomicInteger();
        volatile boolean holds;
        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() { }
        @Override public void stop() { stops.incrementAndGet(); }
        @Override public void tearDown() { }
        @Override public void registerService(Service<?> s) { if(s.serviceName().equals("review-service")) holds=true; }
        @Override public void deRegisterService(Service<?> s) { if(s.serviceName().equals("review-service")) throw new IllegalStateException("cannot withdraw"); }
    }
    static class Svc implements Lifecycle {
        final AtomicInteger stops = new AtomicInteger();
        @Override public void init() { }
        @Override public void start() { }
        @Override public void stop() { stops.incrementAndGet(); }
        @Override public void tearDown() { }
    }
    static MongooseServer started() {
        var s = new MongooseServer(new MongooseServerConfig()); s.init(); s.start(); return s;
    }
    @Test void foreignHandleCannotStopLocalProcessor() throws Exception {
        var a=started(); var b=started();
        try {
            var pa=new Proc(); var pb=new Proc();
            var aa=a.addProcessor("same","g",null,()->pa); aa.reply().toCompletableFuture().get(5,TimeUnit.SECONDS);
            var bb=b.addProcessor("same","g",null,()->pb); bb.reply().toCompletableFuture().get(5,TimeUnit.SECONDS);
            b.stopProcessor(aa.handle()).toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertEquals(0,pb.stops.get(),"foreign handle must not stop local registration");
        } finally { a.stop(); b.stop(); }
    }
    @Test void aFullMailboxDoesNotMakeWithdrawalLookSuccessful() throws Exception {
        var s=started(); var release=new CountDownLatch(1);
        try {
            var p=new Proc(); s.addEventProcessor("p","g",null,()->p);
            var svc=new Svc(); s.registerService(new Service<>(svc,Svc.class,"review-service"));
            var field=MongooseServer.class.getDeclaredField("composingEventProcessorAgents"); field.setAccessible(true);
            var groups=(java.util.Map<?,?>)field.get(s);
            var runner=groups.get("g"); var groupMethod=runner.getClass().getDeclaredMethod("group"); groupMethod.setAccessible(true);
            var group=(com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent)groupMethod.invoke(runner);
            var entered=new CountDownLatch(1);
            group.runOnAgentThread(()-> { entered.countDown(); try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            for(int i=0;i<group.control().capacity();i++) group.runOnAgentThread(()->{});
            s.removeService("review-service");
            assertEquals(0,svc.stops.get(),"rejected withdrawal must keep resource running");
            release.countDown();
            assertTrue(s.shutdown().complete());
            assertEquals(1,svc.stops.get(),"shutdown owns rejected withdrawal resource");
        } finally { release.countDown(); s.stop(); }
    }
    @Test void shutdownWaitsForAnAlreadyRunningRemovalStopWithoutCallingItTwice() throws Exception {
        var cfg=new MongooseServerConfig(); cfg.setShutdownTimeoutMillis(50);
        var s=new MongooseServer(cfg); s.init(); s.start();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var svc=new Svc() {
            @Override public void stop() {
                super.stop(); entered.countDown();
                try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        var removal=new Thread(()->s.removeService("review-service"));
        try {
            s.registerService(new Service<>(svc,Svc.class,"review-service"));
            removal.start(); assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertFalse(s.shutdown().complete(),"in-progress removed service still belongs to server");
            release.countDown(); removal.join(5000);
            assertFalse(removal.isAlive());
            assertTrue(s.shutdown().complete());
            assertEquals(1,svc.stops.get(),"shutdown must not stop the removed service a second time");
        } finally { release.countDown(); removal.join(5000); s.stop(); }
    }
    @Test void beforeStartShutdownStillOwnsAnInProgressRemoval() throws Exception {
        var cfg=new MongooseServerConfig(); cfg.setShutdownTimeoutMillis(50);
        var s=new MongooseServer(cfg);
        var untouched=new Svc(); s.registerService(new Service<>(untouched,Svc.class,"never-started"));
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var removed=new Svc() {
            @Override public void stop() {
                super.stop(); entered.countDown();
                try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        var removal=new Thread(()->s.removeService("review-service"));
        try {
            s.registerService(new Service<>(removed,Svc.class,"review-service"));
            removal.start(); assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertFalse(s.shutdown().complete(),"pre-start removal still owns a running callback");
            release.countDown(); removal.join(5000);
            assertTrue(s.shutdown().complete());
            assertEquals(1,removed.stops.get());
            assertEquals(0,untouched.stops.get(),"ordinary never-started services are not stopped");
        } finally { release.countDown(); removal.join(5000); s.stop(); }
    }
    @Test void failedRemovalIsNotInvokedAgainAtShutdown() {
        assertRemovalFailureIsNotRetried(true);
    }
    @Test void failedPreStartRemovalIsNotInvokedAgainAtShutdown() {
        assertRemovalFailureIsNotRetried(false);
    }
    private void assertRemovalFailureIsNotRetried(boolean start) {
        var s=start ? started() : new MongooseServer(new MongooseServerConfig());
        var svc=new Svc() {
            @Override public void stop() { super.stop(); throw new IllegalStateException("cannot stop"); }
        };
        s.registerService(new Service<>(svc,Svc.class,"review-service"));
        assertThrows(IllegalStateException.class,()->s.removeService("review-service"));
        assertFalse(s.shutdown().complete(),"failed retirement remains reported");
        assertFalse(s.shutdown().complete(),"repeat shutdown preserves failure without retrying user code");
        assertEquals(1,svc.stops.get());
    }
    @Test void explicitServiceLifecycleCallsAreRefusedAfterShutdown() {
        var s=new MongooseServer(new MongooseServerConfig());
        var starts=new AtomicInteger();
        var svc=new Svc() { @Override public void start() { starts.incrementAndGet(); } };
        s.registerService(new Service<>(svc,Svc.class,"review-service")); s.init(); s.start();
        assertTrue(s.shutdown().complete());
        int startsAfterShutdown=starts.get(); int stopsAfterShutdown=svc.stops.get();
        assertThrows(IllegalStateException.class,()->s.startService("review-service"));
        assertThrows(IllegalStateException.class,()->s.stopService("review-service"));
        assertEquals(startsAfterShutdown,starts.get());
        assertEquals(stopsAfterShutdown,svc.stops.get());
    }
    @Test void failedWithdrawalMustNotStopStillReferencedService() {
        var s=started();
        try {
            var p=new Proc(); s.addEventProcessor("p","g",null,()->p);
            var svc=new Svc(); s.registerService(new Service<>(svc,Svc.class,"review-service"));
            assertTrue(p.holds);
            s.removeService("review-service");
            assertEquals(0,svc.stops.get(),"failed withdrawal must retain service until processor stops");
            var replacement=new Svc();
            s.registerService(new Service<>(replacement,Svc.class,"review-service"));
            assertTrue(s.shutdown().complete());
            assertEquals(1,svc.stops.get(),"retained service is stopped once at shutdown");
            assertEquals(1,replacement.stops.get(),"replacement is separately owned and stopped");
        } finally { s.stop(); }
    }
}
