/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.browser.Stub;
import org.agrona.concurrent.IdleStrategy;

import java.util.function.Function;

/**
 * Compile-only stub of {@code com.telamin.mongoose.config.EventSinkConfig}.
 * <p>
 * Scoped to the {@code mongoose-hosted} playground example's sink wiring:
 * {@code name} / {@code instance} / {@code build}. {@code agent} and
 * {@code valueMapper} are kept because they are cheap and pull no extra types.
 *
 * @param <S> the {@link MessageSink} subtype being configured
 */
public class EventSinkConfig<S extends MessageSink<?>> {

    /**
     * Stub of {@code EventSinkConfig.builder()}.
     */
    public static <S extends MessageSink<?>> Builder<S> builder() {
        throw Stub.notRunnable();
    }

    /**
     * Compile-only stub of {@code EventSinkConfig.Builder}.
     *
     * @param <S> the {@link MessageSink} subtype being configured
     */
    public static final class Builder<S extends MessageSink<?>> {

        private Builder() {
        }

        public Builder<S> instance(S instance) {
            throw Stub.notRunnable();
        }

        public Builder<S> name(String name) {
            throw Stub.notRunnable();
        }

        public Builder<S> valueMapper(Function<Object, ?> mapper) {
            throw Stub.notRunnable();
        }

        public Builder<S> agent(String agentName, IdleStrategy idleStrategy) {
            throw Stub.notRunnable();
        }

        public EventSinkConfig<S> build() {
            throw Stub.notRunnable();
        }
    }
}
