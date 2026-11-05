package com.telamin.mongoose.extreview.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.replay.GroupReplayer;
import com.telamin.mongoose.replay.InMemoryEventJournal;
import com.telamin.mongoose.replay.InMemoryReplayStore;
import com.telamin.mongoose.replay.ReplayConfig;
import com.telamin.mongoose.replay.ReplayScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.Set;
import java.util.ConcurrentModificationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The contract tells readers to poll {@code replayers().get(group).stopped(processor)} and {@code complete()} (the
 * tests and the stress oracle do so from the test thread) while the group's agent thread attaches and detaches
 * processors. Hypothesis: GroupReplayer backs these with a plain ArrayList, so an off-thread poll can throw
 * ConcurrentModificationException (or read stale state).
 */
@Timeout(30)
class ReplayStatusApiConcurrencyTest {

    @Test
    void completeAndStoppedAreSafeToPollWhileProcessorsAttachAndDetach() throws Exception {
        GroupReplayer replayer = new GroupReplayer(
                ReplayConfig.replay(Set.of(), Map.of(), new InMemoryEventJournal(), new InMemoryReplayStore()),
                null, new ReplayScheduler());
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<Throwable> first = new AtomicReference<>();
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
        Thread agent = new Thread(() -> {
            int i = 0;
            while (System.nanoTime() < end) {
                DataFlow flow = new DataFlow() { @Override public void onEvent(Object e) { } };
                replayer.attach("p" + (i++ % 64), flow);
                replayer.detach(flow);
            }
        }, "agent");
        Thread poller = new Thread(() -> {
            while (System.nanoTime() < end) {
                try {
                    replayer.complete();
                    replayer.stopped("p1");
                } catch (ConcurrentModificationException | IndexOutOfBoundsException | NullPointerException t) {
                    failures.incrementAndGet();
                    first.compareAndSet(null, t);
                }
            }
        }, "poller");
        agent.start();
        poller.start();
        agent.join();
        poller.join();
        assertEquals(0, failures.get(), "polling the replay status threw " + failures.get() + " time(s); first: " + first.get());
    }
}
