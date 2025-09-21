/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.connector.memory;

import com.telamin.fluxtion.runtime.output.AbstractMessageSink;
import lombok.extern.java.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A simple in-memory message sink that collects all published messages into a list.
 * Useful for testing and local deployments that do not require IO.
 */
@Log
public class InMemoryMessageSink extends AbstractMessageSink<Object> {

    private final List<Object> messages = new CopyOnWriteArrayList<>();

    @Override
    protected void sendToSink(Object value) {
        if (value == null) {
            return;
        }
        messages.add(value);
    }

    /**
     * Returns an immutable snapshot of messages captured so far.
     */
    public List<Object> getMessages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /**
     * Clears captured messages.
     */
    public void clear() {
        messages.clear();
    }
}
