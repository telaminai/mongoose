package com.telamin.mongoose.spike.replay;

import com.telamin.mongoose.service.scheduler.SchedulerService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * SPIKE: timers, recorded and replayed.
 *
 * <p>A timer's action is a closure the node made when it scheduled it, and a live scheduler fires it from its own clock.
 * So a listener on expiry can record WHEN a timer fired, but a replay cannot use that on its own: the replayed node
 * re-arms the same timers, and a live scheduler would fire them from the wall clock. So:
 * <ul>
 *   <li>recording: {@link Recording} numbers each schedule call ({@code seq}, per processor: a replay makes the same
 *   calls in the same order, so the numbers repeat) and, when one fires, reports {@link Fired}{seq, instant} into the
 *   processor's input sequence before running it;</li>
 *   <li>replay: {@link Replaying} never fires by itself. The replay reaches {@code Fired{seq, t}}, pins the clock to
 *   {@code t}, and fires the action the replayed node registered under {@code seq}.</li>
 * </ul>
 * Both keep {@code milliTime()} on the processor's time, so a delay computed from it is the same in both.
 */
public final class TimerReplay {

    /** A timer firing, in the processor's input sequence. */
    public record Fired(long seq, long instant) { }

    private TimerReplay() { }

    /** Numbers schedule calls; {@link #advanceTo} fires what is due, in deadline order, reporting each first. */
    public static final class Recording implements SchedulerService {
        private final TreeMap<Long, List<long[]>> due = new TreeMap<>();          // deadline -> {seq}
        private final Map<Long, Runnable> actions = new java.util.HashMap<>();
        private final LongSupplier now;
        private final Consumer<Fired> onFired;
        private long seq;

        public Recording(LongSupplier now, Consumer<Fired> onFired) {
            this.now = now;
            this.onFired = onFired;
        }

        @Override
        public long scheduleAtTime(long expireTime, Runnable action) {
            long s = ++seq;
            actions.put(s, action);
            due.computeIfAbsent(expireTime, k -> new ArrayList<>()).add(new long[]{s});
            return s;
        }

        @Override
        public long scheduleAfterDelay(long waitTime, Runnable action) {
            return scheduleAtTime(milliTime() + waitTime, action);
        }

        /** The duty cycle's poll: fire every timer due by {@code time}, each reported before it runs. */
        public void advanceTo(long time, Consumer<Long> pinClock) {
            while (!due.isEmpty() && due.firstKey() <= time) {
                var entry = due.pollFirstEntry();
                for (long[] s : entry.getValue()) {
                    onFired.accept(new Fired(s[0], entry.getKey()));
                    pinClock.accept(entry.getKey());
                    actions.remove(s[0]).run();
                }
            }
        }

        @Override public long milliTime() { return now.getAsLong(); }
        @Override public long microTime() { return milliTime() * 1000; }
        @Override public long nanoTime() { return milliTime() * 1_000_000; }
    }

    /** Never fires by itself: the replay fires {@code seq} when it reaches it. */
    public static final class Replaying implements SchedulerService {
        private final Map<Long, Runnable> actions = new java.util.HashMap<>();
        private final LongSupplier now;
        private long seq;

        public Replaying(LongSupplier now) {
            this.now = now;
        }

        @Override
        public long scheduleAtTime(long expireTime, Runnable action) {
            long s = ++seq;
            actions.put(s, action);
            return s;
        }

        @Override
        public long scheduleAfterDelay(long waitTime, Runnable action) {
            return scheduleAtTime(milliTime() + waitTime, action);
        }

        public void fire(long s) {
            Runnable a = actions.remove(s);
            if (a == null) throw new IllegalStateException("the replay fired timer " + s + ", which the replayed run never scheduled");
            a.run();
        }

        @Override public long milliTime() { return now.getAsLong(); }
        @Override public long microTime() { return milliTime() * 1000; }
        @Override public long nanoTime() { return milliTime() * 1_000_000; }
    }
}
