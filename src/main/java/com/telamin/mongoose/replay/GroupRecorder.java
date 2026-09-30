package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.service.admin.impl.AdminCommand;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * RECORD mode for one processor group (spec-replay-recording R2, R4, R6). Everything here runs on the group's agent
 * thread: every Mongoose path into a processor does (R7 makes {@code audit.*} do so too), so a processor's entries are
 * appended in its true input order with no locking.
 */
@Experimental
public final class GroupRecorder {

    private static final Logger log = Logger.getLogger(GroupRecorder.class.getName());

    private final ReplayConfig config;
    private final LongSupplier live;
    private final Map<DataFlow, Recorded> byFlow = new IdentityHashMap<>();

    private static final class Recorded {
        final String name;
        final RecordingClock clock;
        long timerSeq;
        /** Why this processor's recording stopped (its store failed), or null while it records. */
        String broken;
        /** This dispatch: whether the processor was given the input, the copy taken just before, or why none could be. */
        boolean received;
        Object input;
        String uncopyable;
        /** A journalled input this processor received in a state the journal does not hold: recorded inline (N3). */
        boolean notAsJournalled;

        Recorded(String name, RecordingClock clock) {
            this.name = name;
            this.clock = clock;
        }
    }

    public GroupRecorder(ReplayConfig config, LongSupplier live) {
        this.config = config;
        this.live = live;
    }

    /** A processor joins the group: when it is recorded, its clock becomes a live {@link RecordingClock}. */
    public void attach(String name, DataFlow flow) {
        if (!config.covers(name)) return;
        RecordingClock clock = new RecordingClock(live);
        byFlow.put(flow, new Recorded(name, clock));
        flow.setClockStrategy(clock);
    }

    public void detach(DataFlow flow) {
        byFlow.remove(flow);
    }

    /** A ReplayRecord input: pin a recorded processor's clock to its instant; false when {@code flow} is not recorded. */
    public boolean pinSyntheticTime(DataFlow flow, long time) {
        Recorded r = byFlow.get(flow);
        if (r == null) return false;
        r.clock.pin(time);
        return true;
    }

    /** Whether the current dispatch's input is journalled, so recorded by index while it is what the journal holds. */
    private boolean indexedDispatch;
    private String dispatchSource;
    private long dispatchSeq;

    /** Just before a queue dispatches an input of {@code source} (numbered {@code seq}, or -1) to {@code targets}. */
    public void beforeDispatch(String source, long seq, Collection<DataFlow> targets) {
        indexedDispatch = seq >= 0 && config.journalled(source);
        dispatchSource = source;
        dispatchSeq = seq;
        for (DataFlow t : targets) {
            Recorded r = byFlow.get(t);
            if (r == null) continue;
            r.clock.arm();
            r.received = false;
            r.input = null;
            r.uncopyable = null;
            r.notAsJournalled = false;
        }
    }

    /**
     * Just before the strategy gives {@code event} to {@code target} (first attempt only): a copy of it as that
     * processor receives it. Per target, because with fan-out a later processor receives what an earlier one's handler
     * left (review of 90f0d9b, finding 2); committed only by {@link #afterDispatch}, once the dispatch succeeded.
     */
    public void received(DataFlow target, Object event) {
        Recorded r = byFlow.get(target);
        if (r == null || r.broken != null) return;
        r.received = true;
        if (event instanceof UncapturedInput uncaptured) {
            r.uncopyable = uncaptured.reason();         // the strategy cannot say what this processor received (N4)
            r.notAsJournalled = true;
            return;
        }
        if (event instanceof AdminCommand) {
            r.input = event;                            // recorded by name and arguments: not copied
            return;
        }
        if (indexedDispatch) {
            asJournalled(r, event);
            return;
        }
        try {
            r.input = InputCopy.of(event);
        } catch (Throwable t) {
            r.uncopyable = String.valueOf(t);
        }
    }

    /**
     * A journalled input is an index only while this processor receives what the journal holds. With fan-out an earlier
     * processor can change the published object before a later one is given it; both entries then named the one journal
     * item, and the later processor replayed the earlier one's input (re-review N3). So the input as THIS processor
     * receives it is encoded with the feed's own codec and compared with the journal's bytes: equal, it is recorded by
     * index; different, it is recorded inline, as the codec's copy of what it received. A journal that holds no such item
     * (its append failed: see EventToQueuePublisher) keeps the index, so a replay stops at the gap as before.
     */
    private void asJournalled(Recorded r, Object event) {
        r.input = event;
        boolean named = event instanceof com.telamin.fluxtion.runtime.event.NamedFeedEvent<?>;
        try {
            EventCodec codec = config.journalledFeeds().get(dispatchSource);
            byte[] held = config.journal().get(dispatchSource, dispatchSeq);
            Object item = named ? ((com.telamin.fluxtion.runtime.event.NamedFeedEvent<?>) event).data() : event;
            // the payload: the journal's item while this processor received what the journal holds (or the journal lacks
            // it, so a replay stops at the gap); otherwise the FEED'S CODEC's own bytes for what it received (F1: never
            // decoded here and copied by Java serialisation, which is not the codec the configuration chose)
            Object payload = new JournalRef(dispatchSource, dispatchSeq);
            if (held != null) {
                byte[] now = codec.encode(item);
                if (!java.util.Arrays.equals(now, held)) payload = new EncodedInput(dispatchSource, now);
            }
            if (named) {
                // a wrapper is recorded with its own fields whether or not its payload is indexed (F2: rebuilt from the
                // feed's config, a replay gave it another event time, and could not give its topic, delete flag or filter)
                r.input = RecordedNamedEvent.of((com.telamin.fluxtion.runtime.event.NamedFeedEvent<?>) event, payload);
                r.notAsJournalled = true;
            } else if (payload instanceof EncodedInput) {
                r.input = payload;
                r.notAsJournalled = true;
            }
        } catch (Throwable t) {
            r.notAsJournalled = true;
            r.uncopyable = "a journalled input could not be recorded through its feed's codec: " + t;
        }
    }

    /**
     * Just after a dispatch that succeeded first time: one entry per recorded target that was given the input, at the
     * instant it handled it, naming the {@code route} that delivered it. An admin command is recorded by its name and
     * arguments; an input of a journalled feed that carried its sequence number by index; anything else inline, as the
     * copy taken before the processor handled it. An input that could not be copied is marked Failed, so a replay stops
     * there rather than give something other than what was received.
     */
    public void afterDispatch(String source, String route, Object event, long seq, Collection<DataFlow> targets) {
        for (DataFlow t : targets) {
            Recorded r = byFlow.get(t);
            if (r == null) continue;
            List<Long> reads = r.clock.captured();
            long instant = r.clock.instant(reads);
            if (!r.received) continue;                  // the strategy did not give it this processor
            ReplayEntry entry;
            if (event instanceof AdminCommand admin && admin.getArgs() != null && !admin.getArgs().isEmpty()) {
                List<String> args = admin.getArgs();
                entry = new ReplayEntry.AdminInvoked(args.get(0), List.copyOf(args.subList(1, args.size())), instant, reads);
            } else if (seq >= 0 && config.journalled(source) && !r.notAsJournalled) {
                entry = new ReplayEntry.Indexed(source, route, seq, instant, reads);
            } else if (r.uncopyable != null) {
                entry = new ReplayEntry.Failed(source, "the input could not be recorded as received, so it cannot be "
                        + "replayed: " + r.uncopyable, instant);
            } else {
                long number = r.input instanceof RecordedNamedEvent named ? named.sequenceNumber() : -1;
                entry = new ReplayEntry.Inline(source, route, r.input, number, instant, reads);
            }
            r.input = null;
            append(r, entry);
        }
    }

    /** A dispatch threw: marked, not reproduced (D4). */
    public void failed(String source, Object event, Throwable error, Collection<DataFlow> targets) {
        for (DataFlow t : targets) {
            Recorded r = byFlow.get(t);
            if (r == null) continue;
            r.input = null;
            append(r, new ReplayEntry.Failed(source, error + " on " + event, r.clock.instant(r.clock.captured())));
        }
    }

    /**
     * Append {@code entry} to {@code r}'s recording. Never throws: a store that fails is a recording failure, not the
     * processor's, and must not reach the agent (whose error handler may end the process). The first failure is logged,
     * a Failed marker is attempted so a replay stops at the gap rather than skipping it, and that processor's recording
     * stops.
     */
    private void append(Recorded r, ReplayEntry entry) {
        if (r.broken != null) return;
        try {
            config.store().append(r.name, entry);
        } catch (Throwable t) {
            r.broken = String.valueOf(t);
            log.severe("replay recording of " + r.name + " stopped: its store failed on " + entry + ": " + t);
            try {
                config.store().append(r.name, new ReplayEntry.Failed("recording", "the recording stopped here: " + t, entry.instant()));
            } catch (Throwable ignored) {
                // the store cannot take the marker either; the log line above is the record
            }
        }
    }

    /** Why {@code processor}'s recording stopped, or null while it records. */
    public String broken(String processor) {
        for (Recorded r : byFlow.values()) {
            if (r.name.equals(processor)) return r.broken;
        }
        return null;
    }

    /** The next schedule number of {@code flow}, or -1 when it is not recorded. */
    public long nextTimerSeq(DataFlow flow) {
        Recorded r = flow == null ? null : byFlow.get(flow);
        return r == null ? -1 : ++r.timerSeq;
    }

    public void beforeTimer(DataFlow flow) {
        Recorded r = byFlow.get(flow);
        if (r != null) r.clock.arm();
    }

    public void timerFired(DataFlow flow, long seq) {
        Recorded r = byFlow.get(flow);
        if (r == null) return;
        List<Long> reads = r.clock.captured();
        append(r, new ReplayEntry.TimerFired(seq, r.clock.instant(reads), reads));
    }

    /** A timer's action threw: marked, as a dispatch that throws is (D4). */
    public void timerFailed(DataFlow flow, long seq, Throwable error) {
        Recorded r = byFlow.get(flow);
        if (r != null) append(r, new ReplayEntry.Failed("timer#" + seq, String.valueOf(error), r.clock.instant(r.clock.captured())));
    }
}
