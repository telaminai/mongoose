/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.exception;

/**
 * Thrown when publishing or writing to an internal event queue fails.
 */
public class QueuePublishException extends FluxtionServerException {
    public QueuePublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
