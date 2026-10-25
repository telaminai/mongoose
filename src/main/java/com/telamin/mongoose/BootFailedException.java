/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose;

/**
 * A failed {@code bootServer} whose cleanup did not complete (issue #77, external review 3). The original failure is
 * the cause. The partly started server is handed to the caller, who now owns what is still running: call
 * {@code server().shutdown()} again (it resumes) until it completes, or exit the process. When cleanup completes,
 * {@code bootServer} rethrows the original failure instead, with the cleanup's {@link StopResult} as a suppressed
 * exception.
 */
public final class BootFailedException extends RuntimeException {

    private final transient MongooseServer server;
    private final StopResult stopResult;

    public BootFailedException(Throwable cause, MongooseServer server, StopResult stopResult) {
        super("boot failed and the partly started server did not stop completely: " + stopResult + "; cause: " + cause,
                cause);
        this.server = server;
        this.stopResult = stopResult;
    }

    /** The partly started server, still owning what did not stop. */
    public MongooseServer server() {
        return server;
    }

    /** The incomplete result of the cleanup stop. */
    public StopResult stopResult() {
        return stopResult;
    }
}
