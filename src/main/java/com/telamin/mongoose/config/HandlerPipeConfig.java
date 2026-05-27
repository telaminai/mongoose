/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.config;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.input.NamedFeed;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.connector.memory.HandlerPipe;
import com.telamin.mongoose.dutycycle.ServiceAgent;
import com.telamin.mongoose.service.EventSource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import org.agrona.concurrent.IdleStrategy;

import java.util.function.Function;

/**
 * Configuration for an in-VM pipe between two event processors.
 *
 * <p>Declaratively wires both halves of a {@link HandlerPipe} from a single
 * config entry: one half is a {@link Service} of {@link MessageSink} (used by
 * publishing processors) and the other is a {@link Service} of {@link NamedFeed}
 * (used by subscribing processors). Both halves share the underlying
 * {@link com.telamin.mongoose.connector.memory.InMemoryEventSource} so values
 * sent into the sink are dispatched out of the feed.
 *
 * <p>Cross-thread safe by construction — {@code InMemoryEventSource} extends
 * {@code AbstractAgentHostedEventSourceService}, so publishers enqueue and a
 * dedicated agent thread drains into subscribers' read queues. Producers and
 * consumers on different agent groups simply work.
 *
 * <p>Typical YAML:
 * <pre>{@code
 * pipes:
 *   - name: order-events
 *     broadcast: true
 *     agentName: pipe-agent
 *     idleStrategy: !!org.agrona.concurrent.SleepingMillisIdleStrategy {}
 * }</pre>
 *
 * <p>After boot, a publishing processor receives the sink via
 * {@code @ServiceRegistered void onSink(MessageSink<?> sink, String name)} and
 * a subscribing processor reaches it with {@code subscribeToNamedFeed("order-events")}.
 *
 * @param <T> the type of events flowing through the pipe
 */
@Experimental
@Data
@AllArgsConstructor
@NoArgsConstructor
public class HandlerPipeConfig<T> {

    /**
     * The feed-side name — subscribers reach the pipe via this name
     * ({@code subscribeToNamedFeed("order-events")} etc).
     */
    private String name;

    /**
     * The sink-side name — publishers reach the pipe via this name
     * (their {@code @ServiceRegistered void onSink(MessageSink, String name)}
     * fires with this as the second argument). Defaults to
     * {@code <name>.sink} so the two halves don't collide in the
     * name-keyed service registry. Override to use a different
     * convention (e.g. {@code <name>-in}) if it reads better in your
     * domain.
     */
    private String sinkName;

    /**
     * Whether the feed half broadcasts to all registered subscribers (true) or
     * dispatches only on explicit per-subscription keys (false).
     */
    private boolean broadcast = true;

    /**
     * If true, events are wrapped in {@code NamedFeedEvent} on dispatch — see
     * {@link EventSource.EventWrapStrategy}.
     */
    private boolean wrapWithNamedEvent = false;

    /**
     * If true, items offered before {@code startComplete} are cached and
     * replayed once subscribers join.
     */
    private boolean cacheEventLog = false;

    /**
     * Strategy for slow-consumer back-pressure on the feed side.
     */
    private EventSource.SlowConsumerStrategy slowConsumerStrategy = EventSource.SlowConsumerStrategy.BACKOFF;

    /**
     * Optional transformation applied to values as they flow into subscribers.
     * Identity by default.
     */
    private Function<T, ?> valueMapper = Function.identity();

    /**
     * If set, the feed side runs on a dedicated agent thread under this name.
     * Required when the pipe needs its own duty-cycle independent of the
     * publishing processor's agent group.
     */
    private String agentName;

    /**
     * Idle strategy for the agent thread when {@link #agentName} is set.
     */
    private IdleStrategy idleStrategy;

    /**
     * Whether the feed half should be registered as an agent-hosted worker.
     */
    public boolean isAgent() {
        return agentName != null;
    }

    /**
     * Builds the underlying pipe instance plus both Service wrappers in one
     * shot, applying every config field. Called once per pipe at boot by
     * {@code ServerConfigurator}; not intended for application code (use
     * {@link HandlerPipe#of(String)} directly if you want to wire one
     * programmatically).
     */
    @SneakyThrows
    public Built<T> build() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("HandlerPipeConfig.name is required");
        }
        HandlerPipe<T> pipe = HandlerPipe.of(name);
        pipe.cacheEventLog(cacheEventLog);
        if (valueMapper != null) {
            pipe.dataMapper(valueMapper);
        }
        // Derive wrap strategy from broadcast + wrapWithNamedEvent, same matrix
        // EventFeedConfig.toService() uses for consistency.
        EventSource.EventWrapStrategy wrap;
        if (wrapWithNamedEvent && broadcast) {
            wrap = EventSource.EventWrapStrategy.BROADCAST_NAMED_EVENT;
        } else if (!wrapWithNamedEvent && broadcast) {
            wrap = EventSource.EventWrapStrategy.BROADCAST_NOWRAP;
        } else if (wrapWithNamedEvent) {
            wrap = EventSource.EventWrapStrategy.SUBSCRIPTION_NAMED_EVENT;
        } else {
            wrap = EventSource.EventWrapStrategy.SUBSCRIPTION_NOWRAP;
        }
        pipe.getSource().setEventWrapStrategy(wrap);
        pipe.getSource().setSlowConsumerStrategy(slowConsumerStrategy);

        String resolvedSinkName = (sinkName == null || sinkName.isBlank()) ? name + ".sink" : sinkName;

        @SuppressWarnings({"rawtypes", "unchecked"})
        Service<NamedFeed> feedService = new Service(pipe.getSource(), NamedFeed.class, name);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Service<MessageSink<?>> sinkService = new Service(pipe.sink(), MessageSink.class, resolvedSinkName);

        return new Built<>(pipe, feedService, sinkService);
    }

    /**
     * Build the agent wrapper for the feed side. Required when {@link #isAgent()}
     * is true; the pipe's source then runs on its own agent thread independent
     * of the publishing processor's group.
     */
    public ServiceAgent<NamedFeed> toFeedServiceAgent(Built<T> built) {
        if (!isAgent()) {
            throw new IllegalStateException("agentName not set on pipe '" + name + "'");
        }
        if (!(built.pipe.getSource() instanceof org.agrona.concurrent.Agent agent)) {
            throw new IllegalStateException("pipe source is not an Agent");
        }
        return new ServiceAgent<>(agentName, idleStrategy, built.feedService, agent);
    }

    /**
     * Container returned by {@link #build()} carrying the live pipe + both
     * Service wrappers. ServerConfigurator uses this to register both halves
     * with one boot pass over the configured pipes.
     */
    public static final class Built<T> {
        public final HandlerPipe<T> pipe;
        public final Service<NamedFeed> feedService;
        public final Service<MessageSink<?>> sinkService;

        public Built(HandlerPipe<T> pipe,
                     Service<NamedFeed> feedService,
                     Service<MessageSink<?>> sinkService) {
            this.pipe = pipe;
            this.feedService = feedService;
            this.sinkService = sinkService;
        }
    }

    // -------- Builder API (mirrors EventFeedConfig / EventSinkConfig) --------

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static final class Builder<T> {
        private String name;
        private String sinkName;
        private boolean broadcast = true;
        private boolean wrapWithNamedEvent;
        private boolean cacheEventLog;
        private EventSource.SlowConsumerStrategy slowConsumerStrategy;
        private Function<T, ?> valueMapper;
        private String agentName;
        private IdleStrategy idleStrategy;

        public Builder<T> name(String n)                                   { this.name = n; return this; }
        public Builder<T> sinkName(String n)                               { this.sinkName = n; return this; }
        public Builder<T> broadcast(boolean b)                             { this.broadcast = b; return this; }
        public Builder<T> wrapWithNamedEvent(boolean w)                    { this.wrapWithNamedEvent = w; return this; }
        public Builder<T> cacheEventLog(boolean c)                         { this.cacheEventLog = c; return this; }
        public Builder<T> slowConsumerStrategy(EventSource.SlowConsumerStrategy s) { this.slowConsumerStrategy = s; return this; }
        public Builder<T> valueMapper(Function<T, ?> m)                    { this.valueMapper = m; return this; }
        public Builder<T> agent(String agentName, IdleStrategy idle)       { this.agentName = agentName; this.idleStrategy = idle; return this; }

        public HandlerPipeConfig<T> build() {
            HandlerPipeConfig<T> cfg = new HandlerPipeConfig<>();
            cfg.setName(name);
            cfg.setSinkName(sinkName);
            cfg.setBroadcast(broadcast);
            cfg.setWrapWithNamedEvent(wrapWithNamedEvent);
            cfg.setCacheEventLog(cacheEventLog);
            if (slowConsumerStrategy != null) cfg.setSlowConsumerStrategy(slowConsumerStrategy);
            if (valueMapper != null) cfg.setValueMapper(valueMapper);
            cfg.setAgentName(agentName);
            cfg.setIdleStrategy(idleStrategy);
            return cfg;
        }
    }
}
