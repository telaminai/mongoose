/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import java.util.Objects;

/**
 * A strongly-typed key for accessing configuration values in a type-safe way.
 * <p>
 * Use ConfigKey to avoid stringly-typed access and unchecked casts.
 * Example:
 * <pre>{@code
 * static final ConfigKey<Integer> BATCH_SIZE = ConfigKey.of("batch.size", Integer.class);
 * int size = configMap.getOrDefault(BATCH_SIZE, 1000);
 * }</pre>
 *
 * @param <T> the value type this key refers to
 */
public final class ConfigKey<T> {
    private final String name;
    private final Class<T> type;

    private ConfigKey(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
    }

    public static <T> ConfigKey<T> of(String name, Class<T> type) {
        return new ConfigKey<>(name, type);
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConfigKey<?> that)) return false;
        return name.equals(that.name) && type.equals(that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }

    @Override
    public String toString() {
        return "ConfigKey{" + name + ":" + type.getSimpleName() + '}';
    }
}
