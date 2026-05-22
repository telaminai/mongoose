/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.mongoose.service.counters.MongooseCountersService;

/**
 * Test-only probe service — captures the {@link MongooseCountersService}
 * reference injected via {@code @ServiceRegistered} so wiring tests can
 * assert which impl the server installed.
 */
public class CountersProbe {

    public MongooseCountersService counters;

    @ServiceRegistered
    public void countersService(MongooseCountersService svc, String name) {
        this.counters = svc;
    }
}
