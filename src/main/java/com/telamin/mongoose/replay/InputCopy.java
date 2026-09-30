package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.event.NamedFeedEvent;

import java.io.Serializable;

/**
 * A detached copy of an input, taken before the processor handles it, so the recording holds what the processor
 * received: not what its handler, or the application reusing the object, left behind (review of 90f0d9b, finding 2).
 * A replay copies again before it delivers, so a replayed handler cannot change the recording either. Values that
 * cannot change are kept as they are; anything else is copied by Java serialisation, and an input that cannot be copied
 * is refused by name, never recorded by reference.
 *
 * <p><b>The contract (re-review N5).</b> A serialised copy is faithful only when the class's serial form carries every
 * part of its state a handler reads. Serialising successfully does not show that. So:
 * <ul>
 *   <li>REFUSED, by name: an input whose own class, or a superclass outside {@code java.*}, declares a non-static
 *       {@code transient} field. Its serial form drops that field by definition, and a replay would give the handler the
 *       field's default (a handler reading 17 live read 0 on replay).</li>
 *   <li>NOT checked, and the event author's responsibility: state held in nested objects, custom {@code writeObject} /
 *       {@code writeReplace} / {@code Externalizable} forms, and anything else the serial form leaves out. No automatic
 *       check can establish that two objects are equivalent to the handler that reads them.</li>
 *   <li>Where that cannot be met, journal the feed with a codec that is faithful for its items: a journalled input is
 *       recorded through the feed's own codec, which the configuration owns.</li>
 * </ul>
 * A refused input is recorded Failed, so a replay stops there by name; live delivery is unaffected.
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
        String dropped = TRANSIENT.get(input.getClass());
        if (dropped != null) {
            throw new IllegalArgumentException("an input of " + input.getClass().getName() + " cannot be copied as received: "
                    + "its transient field " + dropped + " is not in its serial form");
        }
        return CODEC.decode(CODEC.encode(input));
    }

    /** The first non-static transient field a class or its superclasses outside java.* declare, or null. Cached. */
    private static final ClassValue<String> TRANSIENT = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && !c.getName().startsWith("java."); c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (java.lang.reflect.Modifier.isTransient(m) && !java.lang.reflect.Modifier.isStatic(m)) {
                        return c.getSimpleName() + "." + f.getName();
                    }
                }
            }
            return null;
        }
    };

    private static boolean unchangeable(Object o) {
        return o instanceof String || o instanceof Boolean || o instanceof Character || o instanceof Enum<?>
                || o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte
                || o instanceof Double || o instanceof Float
                || o instanceof java.math.BigInteger || o instanceof java.math.BigDecimal;
    }
}
