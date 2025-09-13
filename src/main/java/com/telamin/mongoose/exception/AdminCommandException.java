/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.exception;

/**
 * Wraps exceptions related to admin command publication/processing.
 */
public class AdminCommandException extends FluxtionServerException {
    /**
     * Create an AdminCommandException with a message.
     *
     * @param message detail message
     */
    public AdminCommandException(String message) {
        super(message);
    }

    /**
     * Create an AdminCommandException with message and cause.
     *
     * @param message detail message
     * @param cause   underlying cause
     */
    public AdminCommandException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Create an AdminCommandException with cause.
     *
     * @param cause underlying cause
     */
    public AdminCommandException(Throwable cause) {
        super(cause);
    }
}
