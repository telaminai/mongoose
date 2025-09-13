/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.connector.memory;

import com.fluxtion.runtime.output.AbstractMessageSink;
import com.telamin.mongoose.service.EventSource;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.util.function.Function;

/**
 * HandlerPipe is a lightweight, in-VM pipe for talking between event handlers.
 * It exposes a MessageSink on the publish side and an {@link InMemoryEventSource}
 * on the receive side. Any value sent to the sink is immediately forwarded to the
 * event source using {@link InMemoryEventSource#publishNow(Object)} so it will be
 * dispatched to subscribers according to the source's lifecycle and wrapping
 * configuration.
 *
 * <p>Typical usage:
 * <pre>
 * HandlerPipe<String> pipe = HandlerPipe.of("myFeed");
 * // wire pipe.source() into the server config so handlers can subscribe
 * // publish from any component:
 * pipe.sink().accept("hello");
 * </pre>
 *
 * <p>The pipe uses an internal sink implementation by default, but you can
 * replace the sink with your own {@link AbstractMessageSink} if you need custom
 * mapping or side-effects, as long as it forwards to {@link #forward(Object)}.
 */
@Log
public class HandlerPipe<T> {

    @Getter
    private final InMemoryEventSource<T> source;

    private final PipeSink sink;

    /**
     * Construct a pipe with a feed name; subscribers can subscribe by this name.
     */
    public static <T> HandlerPipe<T> of(String feedName) {
        return new HandlerPipe<>(feedName);
    }

    /**
     * Construct a pipe and set an event wrap strategy for subscribers.
     */
    public static <T> HandlerPipe<T> of(String feedName, EventSource.EventWrapStrategy wrapStrategy) {
        HandlerPipe<T> pipe = new HandlerPipe<>(feedName);
        pipe.source.setEventWrapStrategy(wrapStrategy);
        return pipe;
    }

    /**
     * Create a pipe with the given feed name. The source defaults to SUBSCRIPTION_NOWRAP.
     */
    public HandlerPipe(@NonNull String feedName) {
        this.source = new InMemoryEventSource<>();
        this.source.setName(feedName);
        this.sink = new PipeSink();
    }

    /**
     * Access the publish-side sink. Call {@code accept(value)} to publish into the pipe.
     */
    public AbstractMessageSink<Object> sink() {
        return sink;
    }

    /**
     * Convenience for applying a data mapper to the source (affects subscribers).
     */
    public HandlerPipe<T> dataMapper(Function<T, ?> mapper) {
        source.setDataMapper(mapper);
        return this;
    }

    /**
     * Enable or disable caching on the source before startComplete.
     */
    public HandlerPipe<T> cacheEventLog(boolean cache) {
        source.setCacheEventLog(cache);
        return this;
    }

    /**
     * Forward a value into the event source immediately. Respects the source lifecycle:
     * prior to startComplete and with cache enabled, items are cached; afterwards they
     * are dispatched to subscribers.
     */
    protected void forward(Object value) {
        //noinspection unchecked
        source.publishNow((T) value);
    }

    /**
     * Internal sink implementation that forwards values into the event source.
     */
    private class PipeSink extends AbstractMessageSink<Object> {
        @Override
        protected void sendToSink(Object value) {
            if (value == null) return;
            try {
                forward(value);
            } catch (Throwable t) {
                log.severe("HandlerPipe sink forward failed: " + t);
                throw t;
            }
        }
    }
}
