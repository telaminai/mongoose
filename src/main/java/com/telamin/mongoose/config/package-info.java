/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * Package: config
 * <p>
 * Responsibility
 * - Provides configuration data structures used to assemble a server instance
 * (MongooseServerConfig and related *Config types).
 * - Supplies builder APIs to construct complex configurations fluently.
 * <p>
 * Public API (consumed by other packages)
 * - MongooseServerConfig (input to MongooseServer)
 * - ServiceConfig, EventFeedConfig, EventSinkConfig, EventProcessorGroupConfig,
 * EventProcessorConfig, ThreadConfig.
 * <p>
 * Allowed dependencies
 * - May depend on: com.telamin.mongoose.service.EventSource (for EventWrapStrategy),
 * com.fluxtion.runtime (Service, MessageSink, EventProcessor) for typing.
 * - Must not depend on: dutycycle, server orchestration, or admin/service impls.
 * <p>
 * Notes
 * - Keep these classes as pure data/transformers; no runtime orchestration logic here.
 */
package com.telamin.mongoose.config;