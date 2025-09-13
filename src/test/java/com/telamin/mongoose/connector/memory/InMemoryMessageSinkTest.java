/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.connector.memory;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class InMemoryMessageSinkTest {

    // Expose a public write method to call the protected sendToSink for testing
    static class TestableInMemoryMessageSink extends InMemoryMessageSink {
        public void write(Object value) {
            super.sendToSink(value);
        }
    }

    @Test
    void collectsMessagesInMemory() {
        TestableInMemoryMessageSink sink = new TestableInMemoryMessageSink();
        sink.write("hello");
        sink.write("world");
        Assertions.assertEquals(List.of("hello", "world"), sink.getMessages());

        sink.clear();
        Assertions.assertTrue(sink.getMessages().isEmpty());
    }
}
