package com.telamin.mongoose.control;

import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.internal.LifecycleManager;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.DynamicCompositeAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class CapturePhysicalShutdownTest {
    record Rig(ComposingEventProcessorAgent group, AgentRunner runner, LifecycleManager manager,
               ConcurrentHashMap<String,LifecycleManager.GroupRunner> groups, EventFlowManager flow) implements AutoCloseable {
        StopResult stop() { return manager.stop(true,groups,new ConcurrentHashMap<>(),Map.of(),flow,0,1000); }
        public void close() throws Exception { runner.close(); group.cleanupAfterDeath(); }
    }
    Rig started(CaptureShutdownRetryTest.Resources resources) throws Exception {
        var flow=new EventFlowManager();
        var group=new ComposingEventProcessorAgent("physical-close",flow,null,new DeadWheelScheduler(),new ConcurrentHashMap<>());
        group.setAuditCaptureResources(resources);
        var registration=new ProcessorRegistration("p");
        var add=group.addNamedEventProcessor(()->new NamedEventProcessor("p",new CaptureShutdownRetryTest.Proc(resources,false)),registration);
        var runner=new AgentRunner(new BusySpinIdleStrategy(),t->{},null,group);
        var groups=new ConcurrentHashMap<String,LifecycleManager.GroupRunner>();
        groups.put("physical-close",new LifecycleManager.GroupRunner() {
            public AgentRunner getGroupRunner() { return runner; }
            public DynamicCompositeAgent getGroup() { return group; }
        });
        AgentRunner.startOnThread(runner);
        assertTrue(add.reply().get(5,TimeUnit.SECONDS).applied());
        assertTrue(group.auditStart("p",registration).reply().get(5,TimeUnit.SECONDS).applied());
        return new Rig(group,runner,new LifecycleManager(null),groups,flow);
    }
    @Test void shutdownReportsPendingPhysicalCloseAndResumesAfterItSucceeds() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var resources=new CaptureShutdownRetryTest.Resources() {
            @Override public void closeCapture(Object handle) {
                super.closeCapture(handle); entered.countDown();
                try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        try(var rig=started(resources)) {
            try {
                assertFalse(rig.stop().complete()); assertTrue(entered.await(5,TimeUnit.SECONDS));
                assertEquals(0,rig.group.captureResourcesHeld(),"handed to closer");
                assertEquals(1,rig.group.captureResourceSlotsInUse(),"physical close still owned");
            } finally { release.countDown(); }
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(rig.group.captureResourceSlotsInUse()!=0 && System.nanoTime()<until) Thread.sleep(1);
            assertTrue(rig.stop().complete()); assertEquals(1,resources.closes.get());
        } finally { release.countDown(); }
    }
    @Test void ordinaryAsyncCloseGetsRemainingShutdownBudget() throws Exception {
        var resources=new CaptureShutdownRetryTest.Resources() {
            @Override public void closeCapture(Object handle) {
                try { Thread.sleep(50); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                super.closeCapture(handle);
            }
        };
        try(var rig=started(resources)) {
            assertTrue(rig.stop().complete(),"a pending close should get the available shutdown budget");
            assertEquals(0,rig.group.captureResourceSlotsInUse()); assertEquals(1,resources.closes.get());
        }
    }
    @Test void failedPhysicalCloseKeepsShutdownIncompleteWithoutRepeatingCallback() throws Exception {
        var resources=new CaptureShutdownRetryTest.Resources() {
            @Override public void closeCapture(Object handle) { super.closeCapture(handle); throw new IllegalStateException("physical close failed"); }
        };
        try(var rig=started(resources)) {
            assertFalse(rig.stop().complete());
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(rig.group.captureCloseFailures().isEmpty() && System.nanoTime()<until) Thread.sleep(1);
            assertEquals(1,rig.group.captureCloseFailures().size());
            assertFalse(rig.stop().complete()); assertEquals(1,resources.closes.get());
            assertEquals(1,rig.group.captureResourceSlotsInUse());
        }
    }
}
