package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

import java.io.Serializable;

/**
 * A journalled feed's input as one processor received it, in its FEED'S OWN codec: the bytes that codec wrote, decoded by
 * the same codec on replay. Recorded when what the processor received is not what the journal holds (an earlier processor
 * in a fan-out changed it, or the codec writes different bytes for the same state). Review of cd52628, F1: decoded at
 * capture and then copied by Java serialisation, a value only the codec carried faithfully replayed as its Java form, and
 * an item only the codec could carry was refused as not Serializable. Nothing but the feed's codec reads these bytes.
 */
@Experimental
public record EncodedInput(String source, byte[] bytes) implements Serializable {

    /**
     * The bytes are the recording's OWN, copied from the codec's array (review of 8211858, G1: a codec that reuses its
     * encode buffer rewrote a recorded input with its next item, silently).
     */
    public EncodedInput {
        bytes = bytes.clone();
    }

    /** A copy, each time: a decoder that consumes its input cannot change what the next replay reads (G1). */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
