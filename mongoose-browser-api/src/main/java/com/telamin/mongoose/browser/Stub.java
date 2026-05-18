/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.browser;

/**
 * Shared failure for every stub method body in {@code mongoose-browser-api}.
 * <p>
 * This artifact exists so playground code can be <em>compiled</em> in the
 * browser against the Mongoose API shape. None of it can run. The
 * {@code com.telamin.mongoose.browser} package does not exist in the real
 * Mongoose artifact, so this helper never collides with it.
 */
public final class Stub {

    private Stub() {
    }

    /**
     * @return the exception every stub method throws if invoked.
     */
    public static UnsupportedOperationException notRunnable() {
        return new UnsupportedOperationException(
                "mongoose-browser-api is a compile-only stub of the Mongoose API. "
                        + "It exists so playground code type-checks in the browser; it cannot run. "
                        + "Download the project and build against the real com.telamin:mongoose "
                        + "artifact to run it.");
    }
}
