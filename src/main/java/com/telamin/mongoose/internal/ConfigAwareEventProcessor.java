/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.internal;

import com.fluxtion.runtime.DefaultEventProcessor;
import com.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.config.ConfigListener;
import com.telamin.mongoose.config.ConfigMap;
import lombok.AccessLevel;
import lombok.Getter;

import java.util.Map;

public class ConfigAwareEventProcessor extends DefaultEventProcessor implements ConfigListener {
    @Getter(AccessLevel.PROTECTED)
    protected ConfigMap configMap;
    private ConfigListener configListener = config -> false;

    public ConfigAwareEventProcessor(ObjectEventHandlerNode allEventHandler, Map<Object, Object> contextMap) {
        super(allEventHandler, contextMap);
        if(allEventHandler instanceof ConfigListener) {
            configListener = (ConfigListener) allEventHandler;
        }
    }

    public ConfigAwareEventProcessor(ObjectEventHandlerNode allEventHandler) {
        super(allEventHandler);
        if(allEventHandler instanceof ConfigListener) {
            configListener = (ConfigListener) allEventHandler;
        }
    }

    @Override
    public boolean initialConfig(ConfigMap config) {
        this.configMap = config;
        configListener.initialConfig(config);
        return true;
    }
}
