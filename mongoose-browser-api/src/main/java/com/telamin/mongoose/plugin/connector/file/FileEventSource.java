/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.connector.file;

import com.telamin.mongoose.browser.Stub;

/**
 * Compile-only stub of the file-connector plugin's
 * {@code com.telamin.mongoose.plugin.connector.file.FileEventSource}.
 * <p>
 * The real class extends {@code AbstractAgentHostedEventSourceService}; the
 * playground example only constructs it and calls {@code setFilename}, and
 * passes it to {@code EventFeedConfig.Builder.instance(Object)} — so the stub
 * needs no supertype. Note the {@code plugin.connector} package: the 0.2.12
 * rename retired the old {@code com.telamin.mongoose.connector} path.
 */
public class FileEventSource {

    public FileEventSource() {
    }

    public FileEventSource(int initialBufferSize) {
    }

    public void setFilename(String filename) {
        throw Stub.notRunnable();
    }

    public String getFilename() {
        throw Stub.notRunnable();
    }
}
