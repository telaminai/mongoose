package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class FatalStopReviewTest {
    static class Proc implements DataFlow, Lifecycle {
        final boolean failStop;
        final boolean failTeardown;
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger teardown = new AtomicInteger();
        Proc(boolean failStop, boolean failTeardown) {
            this.failStop = failStop;
            this.failTeardown = failTeardown;
        }
        public void init() { }
        public void addEventFeed(EventFeed feed) { }
        public void onEvent(Object event) { }
        public void start() { }
        public void stop() {
            stops.incrementAndGet();
            if (failStop) throw new StackOverflowError("fatal stop");
        }
        public void tearDown() {
            teardown.incrementAndGet();
            if (failTeardown) throw new StackOverflowError("fatal teardown");
        }
    }

    @Test void fatalStopMustRemainOwnedForPostDeathCleanup() throws Exception {
        assertFatalReleaseRecovered(true, false);
    }

    @Test void fatalTeardownMustContinueCleanupWithoutRepeatingCallbacks() throws Exception {
        assertFatalReleaseRecovered(false, false);
    }

    @Test void fatalCallbackDuringPostDeathCleanupCanBeResumed() throws Exception {
        assertFatalReleaseRecovered(true, true);
    }

    private void assertFatalReleaseRecovered(boolean failStop, boolean failDuringCleanup) throws Exception {
        AtomicInteger timersCancelled = new AtomicInteger();
        var scheduler = new DeadWheelScheduler() {
            @Override public int cancelTimersOf(DataFlow flow) {
                timersCancelled.incrementAndGet();
                return super.cancelTimersOf(flow);
            }
        };
        var group = new ComposingEventProcessorAgent("fatal-stop", new EventFlowManager(), null,
                scheduler, new ConcurrentHashMap<>());
        var proc = new Proc(failStop, !failStop || failDuringCleanup);
        var registration = new ProcessorRegistration("p");
        var add = group.addNamedEventProcessor(() -> new NamedEventProcessor("p", proc), registration);
        var runner = new AgentRunner(new BusySpinIdleStrategy(), t -> {}, null, group);
        AgentRunner.startOnThread(runner);
        assertEquals(com.telamin.mongoose.control.event.ReplyOutcome.APPLIED, add.reply().get(5, TimeUnit.SECONDS).outcome());
        var stop = group.removeEventProcessorByName("p", registration, null);
        stop.reply().get(5, TimeUnit.SECONDS);
        Thread agent = group.control().agentThread();
        agent.join(5000);
        assertFalse(agent.isAlive());
        if (failDuringCleanup) assertThrows(StackOverflowError.class, group::cleanupAfterDeath);
        else group.cleanupAfterDeath();
        group.cleanupAfterDeath();
        group.cleanupAfterDeath();
        assertEquals(1, proc.stops.get(), "a fatal callback must not be invoked again during cleanup");
        assertEquals(1, proc.teardown.get(), "processor removed before stop still needs teardown after death");
        assertEquals(1, timersCancelled.get(), "resource cleanup must continue after fatal lifecycle callbacks");
    }
}
