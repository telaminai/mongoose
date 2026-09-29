package com.telamin.mongoose.replay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;

/** SPIKE: Java serialisation, for items that are {@code Serializable}. A feed configures its own codec. */
public class JavaSerializationCodec implements EventCodec {
    @Override
    public byte[] encode(Object item) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(b)) {
            out.writeObject(item);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot journal " + item, e);
        }
        return b.toByteArray();
    }

    @Override
    public Object decode(byte[] bytes) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return in.readObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
