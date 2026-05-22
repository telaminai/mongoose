/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
/**
 * Per-service health verdict registry — sibling to
 * {@link com.telamin.mongoose.service.counters.MongooseCountersService}.
 *
 * <p>The package publishes two types — {@link com.telamin.mongoose.service.health.MongooseHealthService}
 * and {@link com.telamin.mongoose.service.health.HealthStatus}. The default
 * implementation is in {@code com.telamin.mongoose.internal}.
 *
 * <p>See {@code design-doc/mongoose-counters-and-performance-monitor.md}
 * §"Health service" + Phase 4.5 for the full design.
 */
package com.telamin.mongoose.service.health;
