/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

/**
 * Stub of {@code com.telamin.mongoose.config.ReadStrategy} — a plain enum, so
 * this is a faithful copy of the real constants (an enum has no body to stub).
 * Kept in sync with the real enum by the drift test.
 */
public enum ReadStrategy {
    COMMITED,
    EARLIEST,
    LATEST,
    ONCE_EARLIEST,
    ONCE_LATEST
}
