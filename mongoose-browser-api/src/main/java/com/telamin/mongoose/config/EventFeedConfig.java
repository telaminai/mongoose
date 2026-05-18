/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.telamin.mongoose.browser.Stub;
import org.agrona.concurrent.IdleStrategy;

import java.util.function.Function;

/**
 * Compile-only stub of {@code com.telamin.mongoose.config.EventFeedConfig}.
 * <p>
 * Scoped to the {@code mongoose-hosted} playground example's feed wiring:
 * {@code name} / {@code agent} / {@code broadcast} / {@code instance} /
 * {@code build}. The real builder also carries {@code wrapWithNamedEvent} and
 * {@code slowConsumerStrategy} — omitted here so the stub does not have to
 * shadow {@code EventSource}; add them (and an {@code EventSource} stub) if the
 * example needs them.
 *
 * @param <IN> the feed's input event type
 */
public class EventFeedConfig<IN> {

    /**
     * Stub of {@code EventFeedConfig.builder()}.
     */
    public static <T> Builder<T> builder() {
        throw Stub.notRunnable();
    }

    /**
     * Compile-only stub of {@code EventFeedConfig.Builder}.
     *
     * @param <IN> the feed's input event type
     */
    public static final class Builder<IN> {

        private Builder() {
        }

        public Builder<IN> instance(Object instance) {
            throw Stub.notRunnable();
        }

        public Builder<IN> name(String name) {
            throw Stub.notRunnable();
        }

        public Builder<IN> broadcast(boolean broadcast) {
            throw Stub.notRunnable();
        }

        public Builder<IN> valueMapper(Function<IN, ?> mapper) {
            throw Stub.notRunnable();
        }

        public Builder<IN> agent(String agentName, IdleStrategy idleStrategy) {
            throw Stub.notRunnable();
        }

        public EventFeedConfig<IN> build() {
            throw Stub.notRunnable();
        }
    }
}
