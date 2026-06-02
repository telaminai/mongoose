/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.browser.Stub;

/**
 * Compile-only stub of {@code com.telamin.mongoose.config.EventProcessorConfig}
 * — the named-handler descriptor wrapped around a generated Fluxtion processor.
 * <p>
 * Scoped to the {@code mongoose-hosted} example: {@code name} / {@code handler}
 * / {@code build}. The real builder also has {@code handlerFunction} and
 * {@code handlerBuilder}; add them here only if the example needs them.
 *
 * @param <T> the {@link DataFlow} processor type being configured
 */
public class EventProcessorConfig<T extends DataFlow> {

    public EventProcessorConfig() {
    }

    public EventProcessorConfig(ObjectEventHandlerNode customHandler) {
    }

    /**
     * Stub of {@code EventProcessorConfig.builder()}.
     */
    public static <T extends DataFlow> Builder<T> builder() {
        throw Stub.notRunnable();
    }

    /**
     * Compile-only stub of {@code EventProcessorConfig.Builder}.
     *
     * @param <T> the {@link DataFlow} processor type being configured
     */
    public static final class Builder<T extends DataFlow> {

        private Builder() {
        }

        public Builder<T> handler(T handler) {
            throw Stub.notRunnable();
        }

        public Builder<T> customHandler(ObjectEventHandlerNode node) {
            throw Stub.notRunnable();
        }

        public Builder<T> name(String name) {
            throw Stub.notRunnable();
        }

        public EventProcessorConfig<T> build() {
            throw Stub.notRunnable();
        }
    }
}
