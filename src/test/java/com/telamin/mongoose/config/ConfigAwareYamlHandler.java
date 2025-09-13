/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.config;

import com.fluxtion.runtime.node.ObjectEventHandlerNode;

/**
 * Simple handler used for YAML-based config injection tests.
 * Extends ObjectEventHandlerNode so it can be wrapped by ConfigAwareEventProcessor,
 * implements ConfigListener to receive initial configuration.
 * Exposes the last received configuration via a static field for test assertions.
 */
public class ConfigAwareYamlHandler extends ObjectEventHandlerNode implements ConfigListener {

    public static volatile ConfigMap lastConfig;

    @Override
    public boolean initialConfig(ConfigMap config) {
        lastConfig = config;
        return true;
    }

    @Override
    protected boolean handleEvent(Object event) {
        // no-op for this test; just accept events
        return true;
    }
}
