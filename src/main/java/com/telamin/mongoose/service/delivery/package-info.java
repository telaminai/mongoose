/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * Delivery guarantees for feeds (docs/design/feed-delivery.md): a source attaches its own read position to each item it
 * publishes, Mongoose acknowledges an item back to the source once every subscriber queue has dispatched it (or, for a
 * processor holding a {@link com.telamin.mongoose.service.delivery.ProcessingCheckpoint}, once its state is durable
 * through it), and the source commits the position its own way ({@link com.telamin.mongoose.service.delivery.SourceCommitter}).
 * A source that commits only acknowledged positions is at-least-once across a crash.
 */
package com.telamin.mongoose.service.delivery;
