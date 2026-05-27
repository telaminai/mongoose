/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.servercontrol;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import org.agrona.concurrent.IdleStrategy;

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
     * Adds a DataFlow to the Fluxtion server for processing events.
     *
     * @param processorName the unique name of the processor being added; must not be null or empty
     * @param groupName     the name of the group this processor belongs to; must not be null or empty
     * @param idleStrategy  the strategy to use to handle idle cycles; must not be null
     * @param feedConsumer  a supplier function that provides the DataFlow instance; must not be null
     * @throws IllegalArgumentException if any of the parameters are invalid, such as being null or empty
     */
    void addEventProcessor(
            String processorName,
            String groupName,
            IdleStrategy idleStrategy,
            Supplier<DataFlow> feedConsumer) throws IllegalArgumentException;

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
     * processors such as their name and associated {@code DataFlow}.
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

    /**
     * Register a service dynamically with the running server.
     * <p>
     * The service is added to the global registry, has dependencies
     * injected from existing services, and is broadcast to every
     * already-running event processor — every processor-internal node
     * carrying {@code @ServiceRegistered} for the service's type
     * receives the callback live (subject to per-group agent-thread
     * scheduling).
     * <p>
     * Symmetric with {@link #removeService(String)}. Use this when an
     * operator wants to wire a new feed, sink, or general service into
     * a running server.
     *
     * @param service the service to register; must not be null and must
     *                carry a unique service name
     * @throws com.telamin.mongoose.exception.ServiceRegistrationException
     *         if a service with the same name is already registered
     */
    void registerService(com.telamin.fluxtion.runtime.service.Service<?> service);

    /**
     * Register a named event source (feed) dynamically with the running
     * server. Convenience for wrapping {@code eventSource} in a
     * {@link com.telamin.fluxtion.runtime.service.Service} and
     * delegating to {@link #registerService}.
     */
    <T> void registerEventSource(String sourceName, com.telamin.mongoose.service.EventSource<T> eventSource);

    /**
     * Returns the list of pipes configured for this server. A pipe is
     * a single logical {@code HandlerPipeConfig} entry that produced
     * two underlying service registrations (a {@link com.telamin.fluxtion.runtime.input.NamedFeed}
     * for subscribers + a {@link com.telamin.fluxtion.runtime.output.MessageSink}
     * for publishers). Surfaced for admin / introspection use cases
     * that want to render the pairing as one entity rather than
     * guessing at the sink-name suffix convention.
     * <p>
     * Empty when no pipes were declared via {@code MongooseServerConfig.pipes}.
     */
    default java.util.List<PipeRegistration> registeredPipes() {
        return java.util.Collections.emptyList();
    }

    /**
     * Deregister a previously registered service.
     * <p>
     * Removes from the global registry, stops the service, and
     * broadcasts {@code @ServiceDeregistered} to every running
     * processor — processor-internal nodes with matching
     * {@code @ServiceDeregistered} handlers unbind. No-op when
     * {@code serviceName} isn't registered.
     * <p>
     * Distinct from {@link #stopService(String)} which is the
     * lifecycle-pause callback (the service stays in the registry and
     * can be restarted).
     */
    void removeService(String serviceName);
}
