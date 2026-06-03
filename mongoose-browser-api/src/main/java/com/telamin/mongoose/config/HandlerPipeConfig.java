/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.telamin.mongoose.browser.Stub;
import org.agrona.concurrent.IdleStrategy;

import java.util.function.Function;

/**
 * Compile-only stub of {@code com.telamin.mongoose.config.HandlerPipeConfig}.
 * Scoped to the {@code fluxtion-pipes-demo} example: {@code builder().name()
 * .sinkName().broadcast().agent().build()}.
 *
 * @param <T> payload type carried over the pipe
 */
public class HandlerPipeConfig<T> {

    public static <T> Builder<T> builder() {
        throw Stub.notRunnable();
    }

    public static final class Builder<T> {

        private Builder() {
        }

        public Builder<T> name(String name) {
            throw Stub.notRunnable();
        }

        public Builder<T> sinkName(String sinkName) {
            throw Stub.notRunnable();
        }

        public Builder<T> broadcast(boolean broadcast) {
            throw Stub.notRunnable();
        }

        public Builder<T> wrapWithNamedEvent(boolean wrap) {
            throw Stub.notRunnable();
        }

        public Builder<T> cacheEventLog(boolean cache) {
            throw Stub.notRunnable();
        }

        public Builder<T> valueMapper(Function<T, ?> mapper) {
            throw Stub.notRunnable();
        }

        public Builder<T> agent(String agentName, IdleStrategy idleStrategy) {
            throw Stub.notRunnable();
        }

        public HandlerPipeConfig<T> build() {
            throw Stub.notRunnable();
        }
    }
}