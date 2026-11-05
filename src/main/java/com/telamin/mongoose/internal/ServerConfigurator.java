/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.audit.EventLogControlEvent;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.HandlerPipeConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ServiceConfig;
import com.telamin.mongoose.dutycycle.GlobalErrorHandler;
import com.telamin.mongoose.service.pool.ObjectPoolsRegistry;
import com.telamin.mongoose.service.pool.impl.Pools;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
import com.telamin.mongoose.service.servercontrol.PipeRegistration;
import org.agrona.concurrent.IdleStrategy;

import java.util.Objects;

/**
 * Helper responsible for applying MongooseServerConfig to a MongooseServer instance.
 * Keeps MongooseServer focused and allows easier testing of configuration logic.
 */
public final class ServerConfigurator {

    private ServerConfigurator() {
    }

    /**
     * Boots and configures a MongooseServer instance using the provided configuration and log record listener.
     *
     * @param mongooseServerConfig         the application configuration containing event feeds, sinks, services, and handlers. Must not be null.
     * @param logRecordListener the listener for log records to be used by event processors. Must not be null.
     * @return a fully configured and initialized MongooseServer instance.
     */
    public static MongooseServer bootFromConfig(MongooseServerConfig mongooseServerConfig, LogRecordListener logRecordListener) {
        Objects.requireNonNull(mongooseServerConfig, "mongooseServerConfig must be non-null");
        Objects.requireNonNull(logRecordListener, "logRecordListener must be non-null");

        MongooseServer mongooseServer = new MongooseServer(mongooseServerConfig);
        mongooseServer.setDefaultErrorHandler(new GlobalErrorHandler(mongooseServerConfig.getFatalErrorPolicy()));

        // Register any configured event-to-invocation strategies with the flow manager
        if (mongooseServerConfig.getEventInvokeStrategies() != null && !mongooseServerConfig.getEventInvokeStrategies().isEmpty()) {
            mongooseServerConfig.getEventInvokeStrategies().forEach((type, factory) ->
                    mongooseServer.registerEventMapperFactory(factory, type));
        }

        //root server controller
        mongooseServer.registerService(new Service<>(mongooseServer, MongooseServerController.class, MongooseServerController.SERVICE_NAME));

        //register ObjectPoolService
        mongooseServer.registerService(new Service<>(Pools.SHARED, ObjectPoolsRegistry.class, ObjectPoolsRegistry.SERVICE_NAME));


        // #79 review finding 6: an override keyed by a group the configuration does not define is warned at boot (a
        // group may still be added at runtime, so this is a warning, not a failure)
        java.util.Set<String> configuredGroups = new java.util.HashSet<>();
        if (mongooseServerConfig.getEventHandlers() != null) {
            mongooseServerConfig.getEventHandlers().forEach(g -> configuredGroups.add(g.getAgentName()));
        }
        java.util.function.BiConsumer<String, com.telamin.mongoose.dispatch.FeedOverloadSettings> checkGroups =
                (feed, settings) -> settings.bySubscriberGroup().keySet().stream()
                        .filter(g -> !configuredGroups.contains(g))
                        .forEach(g -> java.util.logging.Logger.getLogger(ServerConfigurator.class.getName()).warning(
                                "feed '" + feed + "': overload override for subscriber group '" + g
                                        + "', which is not a configured processor group"));

        //event sources
        if (mongooseServerConfig.getEventFeeds() != null) {
            mongooseServerConfig.getEventFeeds().forEach(server -> {
                // #79: resolved and validated before registration, so a bad policy fails boot naming the feed
                var overload = server.resolveOverload();
                checkGroups.accept(server.getName(), overload);
                if (server.getName() != null) {
                    mongooseServer.setFeedOverload(server.getName(), overload);
                }
                if (server.isAgent()) {
                    mongooseServer.registerEventFeedWorker(server.toServiceAgent(), server.getValueMapper());
                } else {
                    mongooseServer.registerEventFeed(server.toService(), server.getValueMapper());
                }
            });
        }

        //event sinks
        if (mongooseServerConfig.getEventSinks() != null) {
            mongooseServerConfig.getEventSinks().forEach(server -> {
                if (server.isAgent()) {
                    mongooseServer.registerEventSinkWorker(server.toServiceAgent(), server.getValueMapper());
                } else {
                    mongooseServer.registerEventSink(server.toService(), server.getValueMapper());
                }
            });
        }

        //in-VM pipes — each entry produces TWO service registrations under
        //the same name (a NamedFeed for subscribers + a MessageSink for
        //publishers). Both halves share the underlying InMemoryEventSource
        //queue. ServiceInjector handles the rest: @ServiceRegistered on
        //either type resolves to the correct half by service-class match.
        if (mongooseServerConfig.getPipes() != null) {
            for (com.telamin.mongoose.config.HandlerPipeConfig<?> pipeCfg : mongooseServerConfig.getPipes()) {
                registerPipe(mongooseServer, pipeCfg, checkGroups);
            }
        }

        //services
        if (mongooseServerConfig.getServices() != null) {
            for (ServiceConfig<?> serviceConfig : mongooseServerConfig.getServices()) {
                if (serviceConfig.isAgent()) {
                    mongooseServer.registerWorkerService(serviceConfig.toServiceAgent());
                } else {
                    mongooseServer.registerService(serviceConfig.toService());
                }
            }
        }

        //event handlers
        if (mongooseServerConfig.getEventHandlers() != null) {
            mongooseServerConfig.getEventHandlers().forEach(cfg -> {
                final EventLogControlEvent.LogLevel defaultLogLevel = cfg.getLogLevel() == null ? EventLogControlEvent.LogLevel.INFO : cfg.getLogLevel();
                String groupName = cfg.getAgentName();
                IdleStrategy idleStrategy1 = cfg.getIdleStrategy();
                IdleStrategy idleStrategy = mongooseServerConfig.lookupIdleStrategyWhenNull(idleStrategy1, cfg.getAgentName());
                cfg.getEventHandlers().entrySet().forEach(handlerEntry -> {
                    String name = handlerEntry.getKey();
                    try {
                        mongooseServer.addEventProcessor(
                                name,
                                groupName,
                                idleStrategy,
                                () -> {
                                    var eventProcessorConfig = handlerEntry.getValue();
                                    var eventProcessor = eventProcessorConfig.getEventHandler() == null
                                            ? eventProcessorConfig.getEventHandlerBuilder().get()
                                            : eventProcessorConfig.getEventHandler();
                                    var logLevel = eventProcessorConfig.getLogLevel() == null ? defaultLogLevel : eventProcessorConfig.getLogLevel();
                                    @SuppressWarnings("unckecked")
                                    var configMap = eventProcessorConfig.getConfig();

                                    eventProcessor.setAuditLogProcessor(logRecordListener);
                                    eventProcessor.setAuditLogLevel(logLevel);
                                    eventProcessor.init();

                                    eventProcessor.consumeServiceIfExported(com.telamin.mongoose.config.ConfigListener.class, l -> l.initialConfig(configMap));
                                    return eventProcessor;
                                });
                    } catch (IllegalArgumentException e) {
                        // mongoose#60: a processor name configured twice in one group used to be dropped with only a
                        // warning; it is a misconfiguration, so boot fails naming the group and the processor
                        throw new IllegalStateException("cannot boot server: event processor '" + name
                                + "' is configured more than once in group '" + groupName + "'", e);
                    }
                });
            });
        }

        //start: a failed boot stops what it started, then throws (issue #77 A1 / review round 4): under the default
        // fatalErrorPolicy CONTAIN nothing else would stop the agent threads already running, and the caller never
        // receives the server reference
        try {
            mongooseServer.init();
            mongooseServer.start();
        } catch (Throwable failed) {
            com.telamin.mongoose.StopResult stopped = null;
            try {
                stopped = mongooseServer.shutdown();
            } catch (Throwable stopFailure) {
                if (stopFailure != failed) failed.addSuppressed(stopFailure);
            }
            if (stopped != null && stopped.complete()) {
                failed.addSuppressed(new IllegalStateException("boot failed; the partly started server was stopped: "
                        + stopped));
                throw failed;
            }
            // cleanup incomplete: hand the caller the server, which still owns what is running (external review 3)
            throw new com.telamin.mongoose.BootFailedException(failed, mongooseServer, stopped);
        }

        return mongooseServer;
    }

    /** Registers both halves of a configured pipe under one shared name.
     *  Generic helper so the wildcard {@code HandlerPipeConfig<?>} captures
     *  cleanly into a concrete T at this call site. Also records the pipe
     *  in MongooseServer's pipe registry so admin / introspection surfaces
     *  can render it as a single logical entity. */
    private static <T> void registerPipe(MongooseServer mongooseServer,
                                         HandlerPipeConfig<T> pipeCfg,
                                         java.util.function.BiConsumer<String, com.telamin.mongoose.dispatch.FeedOverloadSettings> checkGroups) {
        HandlerPipeConfig.Built<T> built = pipeCfg.build();
        var overload = pipeCfg.resolveOverload();
        checkGroups.accept(built.feedService.serviceName(), overload);
        mongooseServer.setFeedOverload(built.feedService.serviceName(), overload);
        if (pipeCfg.isAgent()) {
            mongooseServer.registerEventFeedWorker(pipeCfg.toFeedServiceAgent(built), pipeCfg.getValueMapper());
        } else {
            mongooseServer.registerEventFeed(built.feedService, pipeCfg.getValueMapper());
        }
        mongooseServer.registerEventSink(built.sinkService, null);
        mongooseServer.recordPipeRegistration(new PipeRegistration(
                built.feedService.serviceName(),
                built.sinkService.serviceName(),
                pipeCfg.getAgentName(),
                pipeCfg.isBroadcast(),
                pipeCfg.isCacheEventLog()
        ));
    }
}
