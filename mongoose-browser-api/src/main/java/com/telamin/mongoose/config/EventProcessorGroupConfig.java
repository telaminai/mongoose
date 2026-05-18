/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.telamin.mongoose.browser.Stub;
import org.agrona.concurrent.IdleStrategy;

/**
 * Compile-only stub of {@code com.telamin.mongoose.config.EventProcessorGroupConfig}
 * — a named group of event processors sharing one agent thread.
 * <p>
 * Scoped to the {@code mongoose-hosted} example's group wiring:
 * {@code agentName} / {@code idleStrategy} / {@code add} / {@code build}.
 */
public class EventProcessorGroupConfig {

    /**
     * Stub of {@code EventProcessorGroupConfig.builder()}.
     */
    public static Builder builder() {
        throw Stub.notRunnable();
    }

    /**
     * Compile-only stub of {@code EventProcessorGroupConfig.Builder}.
     */
    public static final class Builder {

        private Builder() {
        }

        public Builder agentName(String agentName) {
            throw Stub.notRunnable();
        }

        public Builder idleStrategy(IdleStrategy idleStrategy) {
            throw Stub.notRunnable();
        }

        public Builder add(EventProcessorConfig<?> cfg) {
            throw Stub.notRunnable();
        }

        public EventProcessorGroupConfig build() {
            throw Stub.notRunnable();
        }
    }
}
