package com.telamin.mongoose.control;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Adversarial hunt, a real group on a real AgentRunner. A failing test demonstrates the failure mode. */
@Timeout(20)
class HuntGroupFailureModesTest {

    /**
     * FM: once the group's thread has died, {@code runOnAgentThread} runs the work inline on the CALLER's thread (the
     * dead-thread branch is the same as "not started yet"), instead of refusing it as not running. A dead group's
     * processors are then touched from an admin or helper thread.
     */
    @Test
    void workHandedToADeadGroupMustBeRefusedNotRunOnTheCaller() throws Exception {
        var group = new ComposingEventProcessorAgent("dies", new EventFlowManager(), null, new DeadWheelScheduler(),
                new ConcurrentHashMap<>());
        var reg = new ProcessorRegistration("p");
        var add = group.addNamedEventProcessor(() -> new NamedEventProcessor("p", new RepliesAndServiceLockReviewTest.Proc()), reg);
        var runner = new AgentRunner(new BusySpinIdleStrategy(), t -> { }, null, group);
        AgentRunner.startOnThread(runner);
        try {
            add.reply().get(2, TimeUnit.SECONDS);
            var fatal = group.addNamedEventProcessor(() -> {
                throw new StackOverflowError("simulated fatal command");
            }, new ProcessorRegistration("fatal"));
            fatal.reply().get(2, TimeUnit.SECONDS);
            group.control().agentThread().join(2000);
            assertFalse(group.control().agentThread().isAlive(), "the group's thread has died");
            assertEquals(ControlDriver.GroupState.DEAD, group.control().state());

            AtomicReference<Thread> ranOn = new AtomicReference<>();
            assertThrows(IllegalStateException.class,
                    () -> group.runOnAgentThread(() -> ranOn.set(Thread.currentThread())),
                    "a dead group is not running: the hand-off is refused");
            assertNull(ranOn.get(), "the work ran on " + ranOn.get() + " although the group is dead");
        } finally {
            runner.close();
            group.cleanupAfterDeath();
        }
    }
}
