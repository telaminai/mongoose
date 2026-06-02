/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.telamin.mongoose.browser.Stub;
import org.agrona.concurrent.IdleStrategy;

/**
 * Compile-only stub of {@code com.telamin.mongoose.config.ServiceConfig} —
 * registers a service instance with the Mongoose server. Used by playground
 * examples that wire {@code WebAdminService} or similar services.
 *
 * @param <T> the registered service type
 */
public class ServiceConfig<T> {

    public ServiceConfig() {
    }

    public ServiceConfig(T service, Class<T> serviceClass, String name) {
    }

    public static <T> Builder<T> builder() {
        throw Stub.notRunnable();
    }

    public static final class Builder<T> {

        private Builder() {
        }

        public Builder<T> service(T service) {
            throw Stub.notRunnable();
        }

        public Builder<T> serviceClass(Class<?> clazz) {
            throw Stub.notRunnable();
        }

        public Builder<T> serviceClassName(String className) {
            throw Stub.notRunnable();
        }

        public Builder<T> name(String name) {
            throw Stub.notRunnable();
        }

        public Builder<T> agent(String agentGroup, IdleStrategy idleStrategy) {
            throw Stub.notRunnable();
        }

        public ServiceConfig<T> build() {
            throw Stub.notRunnable();
        }
    }
}