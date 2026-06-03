/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.connector.aeron;

import com.telamin.fluxtion.runtime.output.AbstractMessageSink;
import com.telamin.mongoose.browser.Stub;

/**
 * Compile-only stub of {@code com.telamin.mongoose.plugin.connector.aeron.AeronMessageSink}.
 * Aeron transport is native (Unsafe + DirectBuffer); this stub exists so the
 * two-container playground example type-checks in the browser even though it
 * can only run locally.
 */
public class AeronMessageSink extends AbstractMessageSink<Object> {

    public AeronMessageSink() {
    }

    @Override
    protected void sendToSink(Object value) {
        throw Stub.notRunnable();
    }

    public void setChannel(String channel) {
        throw Stub.notRunnable();
    }

    public void setStreamId(int streamId) {
        throw Stub.notRunnable();
    }

    public void setLaunchEmbeddedDriver(boolean launch) {
        throw Stub.notRunnable();
    }

    public void setAeronDirectoryName(String dir) {
        throw Stub.notRunnable();
    }
}
