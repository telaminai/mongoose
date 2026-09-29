package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

/** How a journalled feed's items are serialised (D5: serialisation is configuration). */
@Experimental
public interface EventCodec {
    byte[] encode(Object item);

    Object decode(byte[] bytes);
}
