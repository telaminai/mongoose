/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.telamin.mongoose.browser.Stub;

/**
 * Compile-only stub of {@code com.telamin.mongoose.config.MongooseServerConfig}.
 * <p>
 * Scoped to the {@code mongoose-hosted} example's top-level wiring: the fluent
 * {@link Builder} with {@code addEventFeed} / {@code addProcessorGroup} /
 * {@code addEventSink} / {@code build}. The real class carries further
 * {@code addProcessor} convenience overloads — mirror them here only as the
 * example grows, guarded by the drift test.
 */
public class MongooseServerConfig {

    /**
     * Stub of {@code MongooseServerConfig.builder()}.
     */
    public static Builder builder() {
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

        public Builder addProcessorGroup(EventProcessorGroupConfig group) {
            throw Stub.notRunnable();
        }

        public Builder addEventSink(EventSinkConfig<?> sink) {
            throw Stub.notRunnable();
        }

        public Builder addPipe(HandlerPipeConfig<?> pipe) {
            throw Stub.notRunnable();
        }

        public Builder addService(ServiceConfig<?> service) {
            throw Stub.notRunnable();
        }

        public Builder addProcessor(String agentName, EventProcessorConfig<?> processor) {
            throw Stub.notRunnable();
        }

        public MongooseServerConfig build() {
            throw Stub.notRunnable();
        }
    }
}
