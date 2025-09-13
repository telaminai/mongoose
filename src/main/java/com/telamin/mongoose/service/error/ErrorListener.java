/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.error;

/**
 * Listener of error events.
 */
@FunctionalInterface
public interface ErrorListener {
    void onError(ErrorEvent event);
}
