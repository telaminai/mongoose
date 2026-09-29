package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.fluxtion.runtime.event.NamedFeedEventImpl;

import java.io.Serializable;

/**
 * A {@link NamedFeedEvent} a processor received, recorded field by field (the event itself is not serialisable) and
 * rebuilt exactly on replay: its own feed name, topic, number and delete flag, whatever the feed's wrap configuration.
 * Before, the wrapper was stripped and rebuilt from the configuration, so an application's own named event on a feed that
 * does not wrap replayed as its bare data (review of 90f0d9b, finding 3).
 */
@Experimental
public record RecordedNamedEvent(String eventFeedName, String topic, long sequenceNumber, boolean delete, Object data)
        implements Serializable {

    static RecordedNamedEvent of(NamedFeedEvent<?> event, Object data) {
        return new RecordedNamedEvent(event.eventFeedName(), event.topic(), event.sequenceNumber(), event.delete(), data);
    }

    /** The event as the processor received it. */
    public NamedFeedEvent<Object> rebuild() {
        return new NamedFeedEventImpl<>(eventFeedName, topic, sequenceNumber, data).delete(delete);
    }
}
