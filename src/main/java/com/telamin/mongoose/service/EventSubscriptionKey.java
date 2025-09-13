/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service;

/**
 * Represents a unique key for subscribing to events in a system.
 * Combines an event source identifier with a callback type to determine
 * the context of the subscription.
 *
 * @param <T> The type of events associated with the subscription.
 * @param eventSourceKey the event source key to subscribe to
 * @param callBackType   the callback type determining how events are delivered
 */
public record EventSubscriptionKey<T>(EventSourceKey<T> eventSourceKey, CallBackType callBackType) {
    // Existing constructors (backward compatible)
    public EventSubscriptionKey(EventSourceKey<T> eventSourceKey,
                                Class<?> callBackClass) {
        this(eventSourceKey, CallBackType.forClass(callBackClass));
    }

    /**
     * Create a subscription key for a specific event source and call back type.
     * qualifier is ignored (kept for non-breaking backward compatibility).
     */
    public EventSubscriptionKey(EventSourceKey<T> eventSourceKey, CallBackType callBackType, Object qualifier) {
        this(eventSourceKey, callBackType);
    }

    // -------- Fluent API --------

    /**
     * Fluent: Create an onEvent subscription to the named source.
     */
    public static <T> EventSubscriptionKey<T> onEvent(String sourceName) {
        return new EventSubscriptionKey<>(EventSourceKey.of(sourceName), CallBackType.ON_EVENT_CALL_BACK);
    }

    /**
     * Fluent: Create a subscription to the named source with a specific callback type.
     */
    public static <T> EventSubscriptionKey<T> of(String sourceName, CallBackType callBackType) {
        return new EventSubscriptionKey<>(EventSourceKey.of(sourceName), callBackType);
    }

    /**
     * Fluent: Create a subscription for an existing source key and callback type.
     */
    public static <T> EventSubscriptionKey<T> of(EventSourceKey<T> eventSourceKey, CallBackType callBackType) {
        return new EventSubscriptionKey<>(eventSourceKey, callBackType);
    }

    /**
     * Start a fluent builder for a subscription to the named source.
     */
    public static <T> Builder<T> fromSource(String sourceName) {
        return new Builder<>(EventSourceKey.of(sourceName));
    }

    /**
     * Start a fluent builder for a subscription from an existing EventSourceKey.
     */
    public static <T> Builder<T> builder(EventSourceKey<T> sourceKey) {
        return new Builder<>(sourceKey);
    }

    /**
     * Fluent builder for EventSubscriptionKey.
     *
     * @param <T> the event type associated with the subscription
     */
    public static final class Builder<T> {
        private final EventSourceKey<T> eventSourceKey;
        private CallBackType callBackType = CallBackType.ON_EVENT_CALL_BACK; // sensible default

        private Builder(EventSourceKey<T> eventSourceKey) {
            this.eventSourceKey = eventSourceKey;
        }

        /**
         * Set callback type explicitly.
         */
        public Builder<T> callback(CallBackType type) {
            this.callBackType = type;
            return this;
        }

        /**
         * Set callback type from a callback class.
         */
        public Builder<T> callback(Class<?> callbackClass) {
            this.callBackType = CallBackType.forClass(callbackClass);
            return this;
        }

        /**
         * Convenience to declare the standard onEvent callback.
         */
        public Builder<T> onEvent() {
            this.callBackType = CallBackType.ON_EVENT_CALL_BACK;
            return this;
        }

        /**
         * Build the immutable EventSubscriptionKey.
         */
        public EventSubscriptionKey<T> build() {
            return new EventSubscriptionKey<>(eventSourceKey, callBackType);
        }
    }
}
