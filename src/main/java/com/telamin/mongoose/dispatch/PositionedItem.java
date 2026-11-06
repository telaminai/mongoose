/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

/**
 * An item of a feed that tracks source positions (docs/design/feed-delivery.md, section 5a), as it travels in a
 * subscriber queue: the reader unwraps it, gives the processor the bare item, and exposes the feed's sequence number
 * and the source position through {@link com.telamin.mongoose.service.delivery.DeliveryContext} for the processor's
 * event cycle. {@code item} may itself be a journalled item or a named feed event.
 *
 * @param sequence the feed's publish sequence number (restarts with the server)
 * @param position the source's position after the item (a file's byte offset after the line); stable across restarts
 * @param epoch    the source's epoch (its position space; changes when the source is reset or replaced)
 * @param item     what the queue would have held without the position
 */
public record PositionedItem(long sequence, Object position, long epoch, Object item) {
    @Override
    public String toString() {
        return String.valueOf(item);
    }
}
