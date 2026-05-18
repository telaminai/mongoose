/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.connector.file;

import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.browser.Stub;

/**
 * Compile-only stub of the file-connector plugin's
 * {@code com.telamin.mongoose.plugin.connector.file.FileMessageSink}.
 * <p>
 * The real class extends {@code AbstractMessageSink<Object>}; the stub
 * implements {@link MessageSink} directly so it satisfies the
 * {@code EventSinkConfig.Builder<S extends MessageSink<?>>} bound. {@code accept}
 * is the only abstract obligation ({@code MessageSink} extends
 * {@code Consumer}); {@code setValueMapper} is a {@code MessageSink} default.
 */
public class FileMessageSink implements MessageSink<Object> {

    public FileMessageSink() {
    }

    public void setFilename(String filename) {
        throw Stub.notRunnable();
    }

    public String getFilename() {
        throw Stub.notRunnable();
    }

    @Override
    public void accept(Object value) {
        throw Stub.notRunnable();
    }
}
