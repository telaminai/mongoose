/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
/**
 * Hot-path counters for live throughput on svc-admin-web, future metric
 * exporters, and the health service.
 *
 * <p>The package publishes two types — {@link com.telamin.mongoose.service.counters.MongooseCountersService}
 * and {@link com.telamin.mongoose.service.counters.MongooseCounter}. Both implementations
 * (no-op and Agrona-backed) are in {@code com.telamin.mongoose.internal} and not
 * intended for direct consumer use; pick up the service via {@code @ServiceRegistered}.
 *
 * <p>See {@code design-doc/mongoose-counters-and-performance-monitor.md} for
 * the full design + phasing.
 */
package com.telamin.mongoose.service.counters;
