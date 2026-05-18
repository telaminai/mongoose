/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.browser.Stub;

/**
 * Compile-only stub of {@code com.telamin.mongoose.config.MongooseServerConfig}.
 * <p>
 * Scoped to the {@code mongoose-hosted} playground example: the fluent
 * {@link Builder} ({@code addEventFeed} / {@code addEventSink} / {@code build})
 * and the {@code addProcessor(String, T, String)} group-attach overload. The
 * real class carries further {@code addProcessor} overloads and builder methods
 * — mirror them here only as the example grows, guarded by the drift test.
 */
public class MongooseServerConfig {

    /**
     * Stub of {@code MongooseServerConfig.builder()}.
     */
    public static Builder builder() {
        throw Stub.notRunnable();
    }

    /**
     * Stub of {@code addProcessor(String groupName, T processor, String name)} —
     * attaches a generated Fluxtion processor to a named group.
     */
    public <T extends DataFlow> MongooseServerConfig addProcessor(String groupName, T processor, String name) {
        throw Stub.notRunnable();
    }

    /**
     * Compile-only stub of {@code MongooseServerConfig.Builder}.
     */
    public static final class Builder {

        private Builder() {
        }

        public Builder addEventFeed(EventFeedConfig<?> feed) {
            throw Stub.notRunnable();
        }

        public Builder addEventSink(EventSinkConfig<?> sink) {
            throw Stub.notRunnable();
        }

        public MongooseServerConfig build() {
            throw Stub.notRunnable();
        }
    }
}
