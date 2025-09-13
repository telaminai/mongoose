/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service;

/**
 * Represents a key used to uniquely identify an event source within the system.
 *
 * @param <T> Type parameter representing the type of events associated with the event source.
 * @param sourceName the unique name of the event source
 */
public record EventSourceKey<T>(String sourceName) {
    /**
     * Fluent: create a key from a source name.
     */
    public static <T> EventSourceKey<T> of(String sourceName) {
        return new EventSourceKey<>(sourceName);
    }
}
