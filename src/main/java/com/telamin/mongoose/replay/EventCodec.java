package com.telamin.mongoose.replay;

/** How a journalled feed's items are serialised (D5: serialisation is configuration). */
public interface EventCodec {
    byte[] encode(Object item);

    Object decode(byte[] bytes);
}
