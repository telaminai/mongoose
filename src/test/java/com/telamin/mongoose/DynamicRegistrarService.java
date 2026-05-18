/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
import org.agrona.concurrent.YieldingIdleStrategy;

/**
 * Test fixture for {@link DynamicProcessorRegistrationTest}. Registers a
 * {@link DynamicProcessorRegistrationTest.RecordingProcessor} dynamically
 * from its own {@link #start()} hook — mimics the
 * {@code svc-loader-yaml} / {@code svc-loader-spring} pattern at the
 * lowest possible level (no loader plugin, no Fluxtion compile, just
 * {@link MongooseServerController#addEventProcessor}).
 *
 * <p>Top-level because {@code ServiceConfig.toService()} stores the service
 * class as {@code Class.getCanonicalName()}, which returns
 * {@code Outer.Inner} for nested classes and breaks the subsequent
 * {@code Class.forName(...)} resolution.
 */
public class DynamicRegistrarService implements Lifecycle {

    public volatile DynamicProcessorRegistrationTest.RecordingProcessor dynamicProc;
    public volatile boolean installed;

    private MongooseServerController serverController;

    @ServiceRegistered
    public void wire(MongooseServerController controller, String name) {
        this.serverController = controller;
    }

    @Override public void init() {}

    @Override
    public void start() {
        dynamicProc = new DynamicProcessorRegistrationTest.RecordingProcessor("dynamic");
        dynamicProc.init();
        serverController.addEventProcessor(
                "dynamic-processor",
                "dynamic-group",
                new YieldingIdleStrategy(),
                () -> dynamicProc);
        installed = true;
    }

    @Override public void tearDown() {}
}
