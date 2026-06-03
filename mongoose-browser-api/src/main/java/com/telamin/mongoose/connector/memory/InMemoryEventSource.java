/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.connector.memory;

import com.telamin.mongoose.browser.Stub;

/**
 * Compile-only stub of {@code com.telamin.mongoose.connector.memory.InMemoryEventSource}.
 * Lets playground main() code push events into the seam without depending on
 * the full agent-hosted source implementation.
 *
 * @param <T> the payload type pushed into this source
 */
public class InMemoryEventSource<T> {

    public InMemoryEventSource() {
    }

    public void offer(T value) {
        throw Stub.notRunnable();
    }

    public void publish(T value) {
        throw Stub.notRunnable();
    }

    public void setName(String name) {
        throw Stub.notRunnable();
    }
}
