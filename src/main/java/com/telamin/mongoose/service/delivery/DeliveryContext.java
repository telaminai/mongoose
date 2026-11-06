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
 * <p>
 * <b>Delivery complete</b> (docs/design/feed-delivery.md, section 5b). A node that saves state with the delivery's
 * position registers a callback with {@link #onComplete(Runnable)} during the cycle, and saves in it. The reader runs
 * the callbacks once the processors of the queue have handled the item, <b>after every event the delivery queued
 * reentrantly</b> (a handler or sink calling {@code onEvent}: the generated processor drains them after the first
 * cycle's {@code @AfterEvent}), on the processor group's thread with this context still entered, and <b>before the item
 * counts as dispatched</b>, so before the source can acknowledge or commit it. The rules:
 * <ul>
 *     <li>Only a delivery with a source position has a completion: outside one ({@link #current()} null: another
 *     feed, a timer, a service callback, no Mongoose reader) {@code onComplete} keeps nothing, runs nothing and
 *     returns false; the caller does its work itself.</li>
 *     <li>Callbacks run in registration order. Registering a callback (the same instance) that is already pending is a
 *     no-op, so a node may register on every cycle of the delivery, except that a pending callback registered with no
 *     current processor is claimed by the first processor that registers it again; a callback registered while the
 *     callbacks run (by one of them) is run in the same pass. A synthetic clock is installed as its processor, so a
 *     callback the installation's cycle registers is owned by it.</li>
 *     <li>A processor's dispatch that fails discards the callbacks that processor registered (and any registered
 *     with no current processor); a retry registers them again. Callbacks of processors that handled the item are
 *     kept. A delivery that ends without completing (the group ends, the reader closes) runs none.</li>
 *     <li>A callback that throws is retried, alone, under the reader's retry policy, with the item still not
 *     dispatched (not acknowledged); once the policy gives up it is reported and skipped, the callbacks after it
 *     run, and the item counts as dispatched, as any item a failure policy has decided on.</li>
 *     <li>{@link #attempt()} counts the failed dispatch attempts of the current item, so a node can tell a retry of a
 *     cycle it completed (attempt grew) from a further cycle of the same attempt (an event queued reentrantly).</li>
 *     <li>A replay (REPLAY mode) runs the callbacks of each replayed input that had a position the same way, inside
 *     the input's replay, with no retry: a callback that throws stops that processor's replay.</li>
 * </ul>
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
        /** Failed dispatch attempts of the current item. */
        int attempt;
        /** Callbacks pending for the current delivery; created on first use, reused. */
        java.util.ArrayDeque<Completion> completions;
        /** Callbacks registered for the current delivery, to bound a callback that keeps registering itself. */
        int registered;
    }

    /** At most this many callbacks are registered for one delivery: more is a callback that registers itself. */
    public static final int MAX_COMPLETIONS_PER_DELIVERY = 65_536;

    /**
     * A callback registered for the current delivery and the processor that was being dispatched when it was
     * registered ({@code owner}, null when the dispatch strategy names none).
     */
    public record Completion(com.telamin.fluxtion.runtime.DataFlow owner, Runnable callback) {
        /** Run the callback with its owner as the current processor, as during the cycle that registered it. */
        public void run() {
            if (owner == null) {
                callback.run();
                return;
            }
            com.telamin.fluxtion.runtime.DataFlow outer = com.telamin.mongoose.dispatch.ProcessorContext.currentProcessor();
            com.telamin.mongoose.dispatch.ProcessorContext.setCurrentProcessor(owner);
            try {
                callback.run();
            } finally {
                if (outer == null) com.telamin.mongoose.dispatch.ProcessorContext.removeCurrentProcessor();
                else com.telamin.mongoose.dispatch.ProcessorContext.setCurrentProcessor(outer);
            }
        }
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

    /**
     * Run {@code callback} when the current delivery is complete: after the processors of the queue have handled the
     * item, including every event it queued reentrantly, and before the item counts as dispatched (so before the
     * source can acknowledge or commit it). On the processor group's thread, with this context still entered. See the
     * class notes for failures, retries and replay.
     *
     * @return true when registered (or already pending: the same instance is kept once); false, and nothing kept,
     * when the calling thread is handling no delivery with a source position
     * @throws IllegalStateException when more than {@link #MAX_COMPLETIONS_PER_DELIVERY} callbacks were registered
     *                               for one delivery
     */
    public static boolean onComplete(Runnable callback) {
        java.util.Objects.requireNonNull(callback, "callback");
        Current c = CURRENT.get();
        if (c.position == null) return false;
        if (c.completions == null) {
            c.completions = new java.util.ArrayDeque<>(4);
        } else {
            com.telamin.fluxtion.runtime.DataFlow current = null;
            boolean looked = false;
            for (java.util.Iterator<Completion> it = c.completions.iterator(); it.hasNext(); ) {
                Completion pending = it.next();
                if (pending.callback() != callback) continue;
                if (pending.owner() == null) {
                    if (!looked) {
                        current = com.telamin.mongoose.dispatch.ProcessorContext.currentProcessor();
                        looked = true;
                    }
                    if (current != null) {
                        // registered first with no current processor (hook recheck F2): a processor's dispatch that
                        // registers it again claims it, in place, so another processor's failure no longer discards it
                        replaceOwner(c.completions, pending, current);
                    }
                }
                return true;
            }
        }
        if (++c.registered > MAX_COMPLETIONS_PER_DELIVERY) {
            throw new IllegalStateException("more than " + MAX_COMPLETIONS_PER_DELIVERY + " completion callbacks for"
                    + " the delivery of " + c.feed + "#" + c.sequence + ": a callback that registers itself?");
        }
        c.completions.add(new Completion(com.telamin.mongoose.dispatch.ProcessorContext.currentProcessor(), callback));
        return true;
    }

    /** Give {@code pending} the owner {@code owner}, keeping its place in the order. */
    private static void replaceOwner(java.util.ArrayDeque<Completion> completions, Completion pending,
                                     com.telamin.fluxtion.runtime.DataFlow owner) {
        java.util.ArrayDeque<Completion> reordered = new java.util.ArrayDeque<>(completions.size());
        for (Completion p : completions) reordered.add(p == pending ? new Completion(owner, p.callback()) : p);
        completions.clear();
        completions.addAll(reordered);
    }

    /** As {@link #onComplete(Runnable)}, for an injected instance. */
    public boolean whenComplete(Runnable callback) {
        return onComplete(callback);
    }

    /**
     * How many dispatch attempts of the current item have failed so far (0 on the first, and outside a delivery). A
     * retried processor is given the item again with a greater count; an event it queued reentrantly runs with the
     * same count as the cycle that queued it.
     */
    public static int attempt() {
        Current c = CURRENT.get();
        return c.position == null ? 0 : c.attempt;
    }

    /** Internal: the reader takes the next pending callback of the current delivery, in order, or null. */
    public static Completion takeCompletion() {
        java.util.ArrayDeque<Completion> pending = CURRENT.get().completions;
        return pending == null ? null : pending.poll();
    }

    /**
     * Internal: a dispatch attempt of the current item failed in {@code processor} (null: not known). Its callbacks,
     * and those registered with no current processor, are discarded (every callback when the processor is not known);
     * the failed attempt is counted ({@link #attempt()}).
     */
    public static void dispatchFailed(com.telamin.fluxtion.runtime.DataFlow processor) {
        Current c = CURRENT.get();
        if (c.position == null) return;
        c.attempt++;
        if (c.completions == null) return;
        if (processor == null) {
            c.completions.clear();
        } else {
            c.completions.removeIf(p -> p.owner() == null || p.owner() == processor);
        }
    }

    /**
     * Internal: run every pending callback of the current delivery, in order, with no retry (a replay). A callback
     * that throws discards the rest and the throw propagates.
     */
    public static void runCompletions() {
        Completion next;
        try {
            while ((next = takeCompletion()) != null) next.run();
        } catch (RuntimeException | Error e) {
            java.util.ArrayDeque<Completion> pending = CURRENT.get().completions;
            if (pending != null) pending.clear();
            throw e;
        }
    }

    /** Internal: the reader enters an item's event cycle. */
    public static void enter(String feed, long sequence, long epoch, Object position) {
        Current c = CURRENT.get();
        c.feed = feed;
        c.sequence = sequence;
        c.epoch = epoch;
        c.position = position;
        c.attempt = 0;
        c.registered = 0;
        if (c.completions != null) c.completions.clear();
    }

    /** Internal: the reader leaves the event cycle; callbacks still pending are discarded, never run. */
    public static void exit() {
        Current c = CURRENT.get();
        c.feed = null;
        c.position = null;
        c.sequence = 0;
        c.epoch = 0;
        c.attempt = 0;
        c.registered = 0;
        if (c.completions != null) c.completions.clear();
    }
}
