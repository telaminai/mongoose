/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 */

package com.telamin.mongoose.config;

/**
 * Interface for components that need to respond to configuration changes in the system.
 * Implementers can handle both initial configuration and subsequent configuration updates.
 */
public interface ConfigListener {

    /**
     * Called when initial configuration is provided to the system.
     *
     * @param config the initial configuration map containing all configuration parameters
     * @return true if the configuration was successfully applied, false otherwise
     */
    boolean initialConfig(ConfigMap config);

    /**
     * Called when configuration changes occur during runtime.
     * Default implementation returns false, indicating no changes were processed.
     *
     * @param config the configuration update containing changed parameters
     * @return true if the configuration update was successfully applied, false otherwise
     */
    default boolean configChanged(ConfigUpdate config) {
        return false;
    }
}
