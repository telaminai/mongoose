/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.servercontrol;

import com.fluxtion.agrona.concurrent.IdleStrategy;
import com.fluxtion.runtime.StaticEventProcessor;
import com.fluxtion.runtime.service.Service;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;

import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The MongooseServerController interface provides control over the lifecycle and management
 * of event processors and services within a Fluxtion server instance. It allows for the
 * addition and removal of event processors, starting and stopping services, and querying
 * registered components.
 */
public interface MongooseServerController {

    String SERVICE_NAME = "com.telamin.mongoose.service.servercontrol.MongooseServerController";

    /**
     * Adds a StaticEventProcessor to the Fluxtion server for processing events.
     *
     * @param processorName the unique name of the processor being added; must not be null or empty
     * @param groupName     the name of the group this processor belongs to; must not be null or empty
     * @param idleStrategy  the strategy to use to handle idle cycles; must not be null
     * @param feedConsumer  a supplier function that provides the StaticEventProcessor instance; must not be null
     * @throws IllegalArgumentException if any of the parameters are invalid, such as being null or empty
     */
    void addEventProcessor(
            String processorName,
            String groupName,
            IdleStrategy idleStrategy,
            Supplier<StaticEventProcessor> feedConsumer) throws IllegalArgumentException;

    /**
     * Stops a service identified by the specified service name. This method
     * is typically used to terminate the operation of a specific service
     * managed by the server.
     *
     * @param serviceName the name of the service to be stopped
     */
    void stopService(String serviceName);

    /**
     * Retrieves a map of all registered services within the Fluxtion server.
     * The map's keys represent the service names, and the corresponding values
     * are the service instances associated with those names.
     *
     * @return a map where the keys are the names of the registered services
     * and the values are instances of the {@code Service<?>} associated
     * with those names.
     */
    Map<String, Service<?>> registeredServices();

    /**
     * Starts a service identified by the specified service name. This method
     * is typically used to initialize and begin the operation of a specific
     * service managed by the server.
     *
     * @param serviceName the name of the service to be started
     */
    void startService(String serviceName);

    /**
     * Retrieves a map of registered event processors grouped by their respective group names.
     * <p>
     * The keys of the map represent the group names, and the values are collections
     * of {@code NamedEventProcessor} objects, which contain details of individual
     * processors such as their name and associated {@code StaticEventProcessor}.
     *
     * @return a map where the keys are group names and the values are collections
     * of {@code NamedEventProcessor} objects representing the registered
     * event processors.
     */
    Map<String, Collection<NamedEventProcessor>> registeredProcessors();

    /**
     * Stops the event processor associated with the specified group and processor names.
     * This method is used to terminate the operation of a specific event processor
     * managed by the server.
     *
     * @param groupName     the name of the group to which the processor belongs
     * @param processorName the name of the processor to be stopped
     */
    void stopProcessor(String groupName, String processorName);
}
