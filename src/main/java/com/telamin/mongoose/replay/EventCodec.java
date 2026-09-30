package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

/**
 * How a journalled feed's items are serialised (D5: serialisation is configuration).
 *
 * <p><b>Byte ownership.</b> The recording never relies on a codec leaving its arrays alone: bytes it keeps from
 * {@link #encode} are copied at once, and a recorded input is decoded from a copy. So a codec may reuse one buffer for
 * every encode, and a decoder may consume or clear its input (review of #47 at 8211858, G1). A codec must still be
 * FAITHFUL for its items (decode(encode(x)) is what a handler reads from x); nothing here can check that, and the
 * configuration owns it. The journal itself is outside this: what it stores and returns is the journal's.
 */
@Experimental
public interface EventCodec {
    byte[] encode(Object item);

    Object decode(byte[] bytes);
}
