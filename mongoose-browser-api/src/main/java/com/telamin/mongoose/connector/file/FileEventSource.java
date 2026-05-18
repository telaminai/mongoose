/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.connector.file;

import com.telamin.mongoose.browser.Stub;
import com.telamin.mongoose.config.ReadStrategy;

/**
 * Compile-only stub of the core file connector
 * {@code com.telamin.mongoose.connector.file.FileEventSource}.
 * <p>
 * The real class extends {@code AbstractAgentHostedEventSourceService}; the
 * example only constructs it and calls {@code setFilename} / {@code setReadStrategy},
 * then passes it to {@code EventFeedConfig.Builder.instance(Object)} — so the
 * stub needs no supertype.
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

    public void setReadStrategy(ReadStrategy readStrategy) {
        throw Stub.notRunnable();
    }
}
