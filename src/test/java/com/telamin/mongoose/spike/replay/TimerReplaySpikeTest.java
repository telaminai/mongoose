package com.telamin.mongoose.spike.replay;

import com.telamin.fluxtion.runtime.DefaultEventProcessor;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.service.scheduler.SchedulerService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * SPIKE (timers): a processor that arms a timer on each order. Recorded, the stream is the inputs with the timer
 * firings between them; replayed with a scheduler that fires only when the replay says, the processor does exactly
 * what it did. Driven directly (Mongoose hard-wires its scheduler, MongooseServer.java:740, so installing these needs
 * a scheduler factory in config), with the duty cycle's order: due timers fire before the next input.
 */
class TimerReplaySpikeTest {

    static final long T0 = 1_767_258_000_000L;          // 2026-01-01T09:00:00Z, DEMO

    /** An input in the recorded stream. */
    record Input(Object event, long instant) { }

    /** Arms a 50 ms timeout on each order; the timeout reports the clock it read and how many orders it saw. */
    public static class TimeoutNode extends ObjectEventHandlerNode {
        final List<String> emitted = new ArrayList<>();
        private SchedulerService scheduler;
        private int orders;

        @ServiceRegistered
        public void scheduler(SchedulerService scheduler, String name) {
            this.scheduler = scheduler;
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof String order) {
                orders++;
                long armedAt = getContext().getClock().getWallClockTime();
                emitted.add("order=" + order + " time=" + armedAt);
                scheduler.scheduleAfterDelay(50, () -> emitted.add("timeout for=" + order + " armedAt=" + armedAt
                        + " orders=" + orders + " time=" + getContext().getClock().getWallClockTime()));
            }
            return true;
        }
    }

    static DefaultEventProcessor processor(TimeoutNode node, SchedulerService scheduler, AtomicLong clock) {
        DefaultEventProcessor p = new DefaultEventProcessor(node);
        p.init();
        p.setClockStrategy(clock::get);
        p.registerService(new Service<>(scheduler, SchedulerService.class));
        p.start();
        return p;
    }

    /** The live run: each input at its instant, due timers first, then the tail. Returns the recorded stream. */
    static List<Object> record(TimeoutNode node, List<Input> inputs, long end) {
        AtomicLong clock = new AtomicLong(T0);
        List<Object> stream = new ArrayList<>();
        TimerReplay.Recording scheduler = new TimerReplay.Recording(clock::get, stream::add);
        DefaultEventProcessor p = processor(node, scheduler, clock);
        for (Input in : inputs) {
            scheduler.advanceTo(in.instant(), clock::set);
            clock.set(in.instant());
            stream.add(in);
            p.onEvent(in.event());
        }
        scheduler.advanceTo(end, clock::set);
        return stream;
    }

    static List<String> replay(List<Object> stream) {
        AtomicLong clock = new AtomicLong(T0);
        TimerReplay.Replaying scheduler = new TimerReplay.Replaying(clock::get);
        TimeoutNode node = new TimeoutNode();
        DefaultEventProcessor p = processor(node, scheduler, clock);
        for (Object item : stream) {
            if (item instanceof Input in) {
                clock.set(in.instant());
                p.onEvent(in.event());
            } else if (item instanceof TimerReplay.Fired fired) {
                clock.set(fired.instant());
                scheduler.fire(fired.seq());
            }
        }
        return node.emitted;
    }

    @Test
    void timersRecordedAsFirings_replayAtTheSamePointsInTheInputs() {
        // ord-2 arrives before ord-1's timeout (at +60) fires; ord-3 after it
        List<Input> inputs = List.of(new Input("ord-1", T0 + 10), new Input("ord-2", T0 + 30), new Input("ord-3", T0 + 100));
        TimeoutNode live = new TimeoutNode();
        List<Object> stream = record(live, inputs, T0 + 1000);

        assertEquals(List.of("order=ord-1 time=" + (T0 + 10), "order=ord-2 time=" + (T0 + 30),
                "timeout for=ord-1 armedAt=" + (T0 + 10) + " orders=2 time=" + (T0 + 60),
                "timeout for=ord-2 armedAt=" + (T0 + 30) + " orders=2 time=" + (T0 + 80),
                "order=ord-3 time=" + (T0 + 100),
                "timeout for=ord-3 armedAt=" + (T0 + 100) + " orders=3 time=" + (T0 + 150)), live.emitted);
        // the recorded stream: the inputs, with each firing where it happened
        assertEquals(List.of(inputs.get(0), inputs.get(1), new TimerReplay.Fired(1, T0 + 60), new TimerReplay.Fired(2, T0 + 80),
                inputs.get(2), new TimerReplay.Fired(3, T0 + 150)), stream);

        assertEquals(live.emitted, replay(stream), "the replay fires each timer at the same point, at the same instant");

        // witness: the inputs alone, without the firings, do not reproduce the run
        List<Object> inputsOnly = stream.stream().filter(i -> i instanceof Input).toList();
        assertNotEquals(live.emitted, replay(inputsOnly), "the timer firings are inputs a replay needs");
    }
}
