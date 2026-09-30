package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.fluxtion.runtime.event.NamedFeedEventImpl;

import java.io.Serializable;

/**
 * A {@link NamedFeedEvent} a processor received, recorded field by field (the event itself is not serialisable) and
 * rebuilt exactly on replay: its own feed name, topic, number, delete flag and event time, whatever the feed's wrap
 * configuration. Before, the wrapper was stripped and rebuilt from the configuration, so an application's own named event
 * on a feed that does not wrap replayed as its bare data (review of 90f0d9b, finding 3).
 *
 * <p>Only {@link NamedFeedEventImpl} itself is captured: its fields are all known. Its constructors stamp the wall clock,
 * so the recorded {@code eventTime} is set back after construction (re-review N2: a replay said the replaying machine's
 * time). Any other implementation, a subclass included, is REFUSED by name ({@link #of} throws, and the recording marks
 * that input failed): rebuilt as the base class it would silently lose its own state.
 *
 * <p>Both filters are kept: the string filter is the feed name, and the INTEGER filter, which the base class's own
 * constructors never set but its public {@code copyFrom} copies from another instance, is recorded and set back (review
 * of cd52628, F2: an exact NamedFeedEventImpl given filter 17 replayed with the default, which can select another handler).
 */
@Experimental
public record RecordedNamedEvent(String eventFeedName, String topic, long sequenceNumber, boolean delete, long eventTime,
                                 int filterId, Object data) implements Serializable {

    static RecordedNamedEvent of(NamedFeedEvent<?> event, Object data) {
        if (event.getClass() != NamedFeedEventImpl.class) {
            throw new IllegalArgumentException("a named event of " + event.getClass().getName() + " cannot be recorded field "
                    + "by field: only NamedFeedEventImpl's fields are known, and rebuilt as it this one would lose its own state");
        }
        return new RecordedNamedEvent(event.eventFeedName(), event.topic(), event.sequenceNumber(), event.delete(),
                event.getEventTime(), event.filterId(), data);
    }

    /** The event as the processor received it. */
    public NamedFeedEvent<Object> rebuild() {
        return rebuild(data);
    }

    /** As {@link #rebuild()}, around {@code payload}: the data as the replay resolved it (a journal item, a codec's copy). */
    public NamedFeedEvent<Object> rebuild(Object payload) {
        NamedFeedEventImpl<Object> event = new NamedFeedEventImpl<>(eventFeedName, topic, sequenceNumber, payload).delete(delete);
        if (filterId != event.filterId()) {
            event.copyFrom(new WithFilterId(filterId, eventFeedName, topic, sequenceNumber, payload, delete));
        }
        event.setEventTime(eventTime);                  // the constructor stamped the wall clock
        return event;
    }

    /** The one way to give the base class an integer filter: copyFrom an instance that has it (it copies filterId). */
    private static final class WithFilterId extends NamedFeedEventImpl<Object> {
        WithFilterId(int filterId, String feed, String topic, long seq, Object data, boolean delete) {
            super(feed, topic, seq, data);
            this.filterId = filterId;
            delete(delete);
        }
    }
}
