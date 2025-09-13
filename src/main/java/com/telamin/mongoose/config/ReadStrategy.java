/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

/**
 * Defines the strategies for reading data from an event source or message queue.
 * These strategies determine the starting point and behavior for consuming messages.
 */
public enum ReadStrategy {
    /**
     * Read only committed messages from the source, typically used in transaction-aware systems.
     */
    COMMITED,
    /**
     * Begin reading from the earliest available message in the source.
     */
    EARLIEST,
    /**
     * Begin reading from the latest/most recent message in the source.
     */
    LATEST,
    /**
     * Read once from the earliest available message, then stop.
     */
    ONCE_EARLIEST,
    /**
     * Read once from the latest/most recent message, then stop.
     */
    ONCE_LATEST
}
