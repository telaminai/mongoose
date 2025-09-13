/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.exception;

/**
 * Base unchecked exception for Fluxtion Server domain.
 */
public class FluxtionServerException extends RuntimeException {
    public FluxtionServerException() {
    }

    public FluxtionServerException(String message) {
        super(message);
    }

    public FluxtionServerException(String message, Throwable cause) {
        super(message, cause);
    }

    public FluxtionServerException(Throwable cause) {
        super(cause);
    }
}
