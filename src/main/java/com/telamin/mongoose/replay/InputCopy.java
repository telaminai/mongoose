package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.event.NamedFeedEvent;

import java.io.Serializable;

/**
 * A detached copy of an input, taken before the processor handles it, so the recording holds what the processor
 * received: not what its handler, or the application reusing the object, left behind (review of 90f0d9b, finding 2).
 * A replay copies again before it delivers, so a replayed handler cannot change the recording either. Values that
 * cannot change are kept as they are; anything else is copied by Java serialisation, and an input that cannot be copied
 * is refused by name, never recorded by reference.
 */
final class InputCopy {
    private InputCopy() { }

    private static final JavaSerializationCodec CODEC = new JavaSerializationCodec();

    static Object of(Object input) {
        if (input == null || unchangeable(input)) return input;
        if (input instanceof NamedFeedEvent<?> named) return RecordedNamedEvent.of(named, of(named.data()));
        if (input instanceof RecordedNamedEvent recorded) {
            return new RecordedNamedEvent(recorded.eventFeedName(), recorded.topic(), recorded.sequenceNumber(),
                    recorded.delete(), recorded.eventTime(), of(recorded.data()));
        }
        if (!(input instanceof Serializable)) {
            throw new IllegalArgumentException("an input of " + input.getClass().getName()
                    + " cannot be copied as received: it is not Serializable");
        }
        return CODEC.decode(CODEC.encode(input));
    }

    private static boolean unchangeable(Object o) {
        return o instanceof String || o instanceof Boolean || o instanceof Character || o instanceof Enum<?>
                || o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte
                || o instanceof Double || o instanceof Float
                || o instanceof java.math.BigInteger || o instanceof java.math.BigDecimal;
    }
}
