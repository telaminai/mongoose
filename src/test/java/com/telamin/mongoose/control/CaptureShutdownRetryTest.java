package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.internal.LifecycleManager;
import com.telamin.mongoose.service.audit.AuditCaptureResources;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.DynamicCompositeAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class CaptureShutdownRetryTest {
    static class Resources implements AuditCaptureResources {
        final AtomicBoolean failUninstall = new AtomicBoolean();
        final AtomicBoolean installed = new AtomicBoolean();
        final AtomicInteger closes = new AtomicInteger();
        public Object openCapture(String name) { return new Object(); }
        public void installCapture(Object handle, String name, DataFlow flow) { installed.set(true); }
        public void uninstallCapture(Object handle, String name, DataFlow flow) {
            if (failUninstall.get()) throw new IllegalStateException("capture detach unavailable");
            installed.set(false);
        }
        public void closeCapture(Object handle) { closes.incrementAndGet(); }
    }

    static class Proc implements DataFlow, Lifecycle {
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger teardowns = new AtomicInteger();
        final Resources resources;
        final boolean fatalStop;
        final AtomicBoolean closedBeforeTeardown = new AtomicBoolean();
        Proc(Resources resources, boolean fatalStop) { this.resources = resources; this.fatalStop = fatalStop; }
        public void init() { }
        public void addEventFeed(EventFeed feed) { }
        public void onEvent(Object event) { }
        public void start() { }
        public void stop() {
            stops.incrementAndGet();
            if (fatalStop) throw new StackOverflowError("fatal stop");
        }
        public void tearDown() {
            closedBeforeTeardown.set(resources.closes.get() != 0);
            teardowns.incrementAndGet();
        }
    }

    @Test
    void aClosedGroupRetainsFailedCaptureCleanupAndTheNextStopResumesIt() throws Exception {
        Resources resources = new Resources();
        Proc proc = new Proc(resources, false);
        EventFlowManager flowManager = new EventFlowManager();
        var group = new ComposingEventProcessorAgent("capture-retry", flowManager, null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        group.setAuditCaptureResources(resources);
        var registration = new ProcessorRegistration("p");
        var add = group.addNamedEventProcessor(() -> new NamedEventProcessor("p", proc), registration);
        var runner = new AgentRunner(new BusySpinIdleStrategy(), t -> { }, null, group);
        var manager = new LifecycleManager(null);
        var groups = new ConcurrentHashMap<String, LifecycleManager.GroupRunner>();
        groups.put("capture-retry", new LifecycleManager.GroupRunner() {
            public AgentRunner getGroupRunner() { return runner; }
            public DynamicCompositeAgent getGroup() { return group; }
        });
        AgentRunner.startOnThread(runner);
        try {
            assertTrue(add.reply().get(5, TimeUnit.SECONDS).applied());
            assertTrue(group.auditStart("p", registration).reply().get(5, TimeUnit.SECONDS).applied());
            Thread agent = group.control().agentThread();
            resources.failUninstall.set(true);
            var first = manager.stop(true, groups, new ConcurrentHashMap<>(), Map.of(), flowManager, 0, 1_000);
            assertFalse(first.complete(), first.toString());
            assertTrue(first.groupsRunning().contains("capture-retry"));
            agent.join(5_000);
            assertFalse(agent.isAlive(), "the runner has ended, only resource cleanup remains");
            assertEquals(ControlDriver.GroupState.CLOSED, group.control().state());
            assertEquals(1, group.captureResourcesHeld());
            assertEquals(0, resources.closes.get());
            assertEquals(1, proc.stops.get());
            assertEquals(1, proc.teardowns.get());

            resources.failUninstall.set(false);
            var second = manager.stop(true, groups, new ConcurrentHashMap<>(), Map.of(), flowManager, 0, 1_000);
            assertTrue(second.complete(), second.toString());
            assertEquals(0, group.captureResourcesHeld());
            assertFalse(resources.installed.get());
            assertEquals(1, resources.closes.get());
            assertEquals(1, proc.stops.get(), "stop must not repeat when retrying a resource detach");
            assertEquals(1, proc.teardowns.get());
            assertTrue(manager.stop(true, groups, new ConcurrentHashMap<>(), Map.of(), flowManager, 0, 1_000).complete());
            assertEquals(1, resources.closes.get());
        } finally {
            resources.failUninstall.set(false);
            runner.close();
            group.cleanupAfterDeath();
        }
    }

    @Test
    void postDeathCaptureCleanupFollowsTheRemainingTeardown() throws Exception {
        Resources resources = new Resources();
        Proc proc = new Proc(resources, true);
        var group = new ComposingEventProcessorAgent("capture-fatal-stop", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        group.setAuditCaptureResources(resources);
        var registration = new ProcessorRegistration("p");
        var add = group.addNamedEventProcessor(() -> new NamedEventProcessor("p", proc), registration);
        var runner = new AgentRunner(new BusySpinIdleStrategy(), t -> { }, null, group);
        AgentRunner.startOnThread(runner);
        try {
            assertTrue(add.reply().get(5, TimeUnit.SECONDS).applied());
            assertTrue(group.auditStart("p", registration).reply().get(5, TimeUnit.SECONDS).applied());
            group.removeEventProcessorByName("p", registration, null).reply().get(5, TimeUnit.SECONDS);
            Thread agent = group.control().agentThread();
            agent.join(5_000);
            assertFalse(agent.isAlive());
            group.cleanupAfterDeath();
            assertEquals(1, proc.stops.get());
            assertEquals(1, proc.teardowns.get());
            assertFalse(proc.closedBeforeTeardown.get(), "remaining lifecycle callbacks precede final resource close");
            assertEquals(1, resources.closes.get());
        } finally {
            runner.close();
            group.cleanupAfterDeath();
        }
    }
}
