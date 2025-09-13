/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.fluxtion.agrona.concurrent.IdleStrategy;
import com.fluxtion.runtime.audit.EventLogControlEvent;
import com.fluxtion.runtime.audit.LogRecordListener;
import com.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ServiceConfig;
import com.telamin.mongoose.dutycycle.GlobalErrorHandler;
import com.telamin.mongoose.service.pool.ObjectPoolsRegistry;
import com.telamin.mongoose.service.pool.impl.Pools;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;

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
        mongooseServer.setDefaultErrorHandler(new GlobalErrorHandler());

        // Register any configured event-to-invocation strategies with the flow manager
        if (mongooseServerConfig.getEventInvokeStrategies() != null && !mongooseServerConfig.getEventInvokeStrategies().isEmpty()) {
            mongooseServerConfig.getEventInvokeStrategies().forEach((type, factory) ->
                    mongooseServer.registerEventMapperFactory(factory, type));
        }

        //root server controller
        mongooseServer.registerService(new Service<>(mongooseServer, MongooseServerController.class, MongooseServerController.SERVICE_NAME));

        //register ObjectPoolService
        mongooseServer.registerService(new Service<>(Pools.SHARED, ObjectPoolsRegistry.class, ObjectPoolsRegistry.SERVICE_NAME));


        //event sources
        if (mongooseServerConfig.getEventFeeds() != null) {
            mongooseServerConfig.getEventFeeds().forEach(server -> {
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
                    } catch (Exception e) {
                        // keep behavior consistent with previous implementation (log handled by MongooseServer)
                    }
                });
            });
        }

        //start
        mongooseServer.init();
        mongooseServer.start();

        return mongooseServer;
    }
}
