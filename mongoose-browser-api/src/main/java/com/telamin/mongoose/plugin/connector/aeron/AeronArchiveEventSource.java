/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.connector.aeron;

import com.telamin.mongoose.browser.Stub;

/**
 * Compile-only stub of
 * {@code com.telamin.mongoose.plugin.connector.aeron.AeronArchiveEventSource}.
 * Aeron transport is native; this stub exists so the two-container playground
 * example type-checks in the browser. Local run only.
 */
public class AeronArchiveEventSource {

    public enum Mode {
        LIVE,
        REPLAY,
        REPLAY_THEN_LIVE
    }

    public AeronArchiveEventSource() {
    }

    public AeronArchiveEventSource(String name) {
    }

    public void setMode(Mode mode) {
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

    public void setName(String name) {
        throw Stub.notRunnable();
    }
}
