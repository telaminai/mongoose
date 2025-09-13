/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import lombok.ToString;

import java.util.Map;

@ToString
public class ConfigMap {

    private final Map<String, Object> configMap;

    public ConfigMap(Map<String, Object> configMap) {
        this.configMap = configMap;
    }

    // Legacy string-based accessors (kept for backward compatibility)
    @Deprecated
    @SuppressWarnings({"unchecked"})
    public <T> T get(String key) {
        return (T) configMap.get(key);
    }

    @Deprecated
    @SuppressWarnings({"unchecked"})
    public <T> T getOrDefault(String key, T defaultValue) {
        return (T) configMap.getOrDefault(key, defaultValue);
    }

    // Type-safe accessors using ConfigKey
    public <T> T get(ConfigKey<T> key) {
        Object value = configMap.get(key.name());
        if (value == null) {
            return null;
        }
        if (!key.type().isInstance(value)) {
            throw new ClassCastException("Configuration value for '" + key.name() + "' is of type "
                    + value.getClass().getName() + ", expected " + key.type().getName());
        }
        return key.type().cast(value);
    }

    public <T> T getOrDefault(ConfigKey<T> key, T defaultValue) {
        Object value = configMap.get(key.name());
        if (value == null) {
            return defaultValue;
        }
        if (!key.type().isInstance(value)) {
            throw new ClassCastException("Configuration value for '" + key.name() + "' is of type "
                    + value.getClass().getName() + ", expected " + key.type().getName());
        }
        return key.type().cast(value);
    }

    public <T> T require(ConfigKey<T> key) {
        Object value = configMap.get(key.name());
        if (value == null) {
            throw new com.telamin.mongoose.exception.ConfigurationException("Required configuration missing: '" + key.name() + "'");
        }
        if (!key.type().isInstance(value)) {
            throw new com.telamin.mongoose.exception.ConfigurationException("Configuration value for '" + key.name() + "' is of type "
                    + value.getClass().getName() + ", expected " + key.type().getName());
        }
        return key.type().cast(value);
    }
}
