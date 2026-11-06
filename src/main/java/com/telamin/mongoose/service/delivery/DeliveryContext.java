/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.delivery;

/**
 * The source position of the event being handled, readable by a processor's nodes during the event cycle, in any
 * handler or {@code @AfterEvent} (docs/design/feed-delivery.md, section 5a). Set by the reader on the processor group's
 * thread for each item of a feed that tracks positions (a {@code COMMITED} file feed), cleared after the cycle; null
 * for an event without a position (another feed, a timer, a service callback).
 * <p>
 * A stateful application stores {@link Delivery#position()} with its state, in the same atomic write, and after a
 * restart skips an event for which {@link Delivery#alreadyApplied} the stored position: the consumer-side offset (the
 * Kafka transactional-state pattern). Static access ({@link #current()}) or, for injection, the registered service
 * {@link #SERVICE_NAME} ({@code @ServiceRegistered public void delivery(DeliveryContext ctx, String name)}).
 */
public final class DeliveryContext {

    public static final String SERVICE_NAME = "mongoose.deliveryContext";
    /** The instance registered as a service; every instance reads the calling thread's delivery. */
    public static final DeliveryContext INSTANCE = new DeliveryContext();

    private static final class Current {
        String feed;
        long sequence;
        long epoch;
        Object position;
    }

    private static final ThreadLocal<Current> CURRENT = ThreadLocal.withInitial(Current::new);

    private DeliveryContext() {
    }

    /** The delivery of the event the calling thread is handling, or null when it has no source position. */
    public static Delivery current() {
        Current c = CURRENT.get();
        return c.position == null ? null : new Delivery(c.feed, c.sequence, c.epoch, c.position);
    }

    /** The source position of the event being handled, or null; no allocation. */
    public static Object currentPosition() {
        return CURRENT.get().position;
    }

    /** As {@link #current()}, for an injected instance. */
    public Delivery delivery() {
        return current();
    }

    /** Internal: the reader enters an item's event cycle. */
    public static void enter(String feed, long sequence, long epoch, Object position) {
        Current c = CURRENT.get();
        c.feed = feed;
        c.sequence = sequence;
        c.epoch = epoch;
        c.position = position;
    }

    /** Internal: the reader leaves the event cycle. */
    public static void exit() {
        Current c = CURRENT.get();
        c.feed = null;
        c.position = null;
        c.sequence = 0;
        c.epoch = 0;
    }
}
