/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * Package: dispatch
 * <p>
 * Responsibility
 * - Defines the event-flow API and infrastructure between event sources and event processors:
 * keys (EventSourceKey, EventSubscriptionKey), routing (EventFlowManager),
 * publishing (EventToQueuePublisher), invocation strategies (EventToInvokeStrategy),
 * and per-thread processor context (ProcessorContext).
 * <p>
 * Public API (consumed by other packages)
 * - EventSource, LifeCycleEventSource, EventSourceKey, EventSubscriptionKey
 * - EventFlowManager (orchestrated by MongooseServer)
 * - EventToQueuePublisher (used by Event sources)
 * - CallBackType and EventToInvokeStrategy (SPI for mapping events -> callbacks)
 * - ProcessorContext (thread-local current DataFlow)
 * <p>
 * Allowed dependencies
 * - May depend on: com.telamin.mongoose.exception (for domain exceptions),
 * com.telamin.fluxtion.runtime (DataFlow, Event types),
 * minimal Agrona concurrency queues.
 * - Must not depend on: com.telamin.mongoose.config, com.telamin.mongoose.service.* (to avoid inward coupling),
 * com.telamin.mongoose.dutycycle (agents consume dispatch, not vice-versa).
 * <p>
 * Notes
 * - Keep this package free of server orchestration logic; MongooseServer wires it up.
 */
package com.telamin.mongoose.dispatch;