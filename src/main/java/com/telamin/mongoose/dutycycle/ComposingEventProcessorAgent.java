/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.fluxtion.agrona.concurrent.DynamicCompositeAgent;
import com.fluxtion.agrona.concurrent.OneToOneConcurrentArrayQueue;
import com.fluxtion.runtime.StaticEventProcessor;
import com.fluxtion.runtime.annotations.feature.Experimental;
import com.fluxtion.runtime.input.EventFeed;
import com.fluxtion.runtime.lifecycle.Lifecycle;
import com.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import com.telamin.mongoose.service.scheduler.SchedulerService;
import lombok.extern.java.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Composite agent that manages named StaticEventProcessor instances within an agent group.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Accepts registrations/removals of named event processors</li>
 *   <li>Wires processors to event queues via the EventFlowManager on first subscription</li>
 *   <li>Registers shared services and a SchedulerService into each processor</li>
 *   <li>Adds/removes queue reader agents dynamically as subscriptions change</li>
 * </ul>
 */
@Experimental
@Log
public class ComposingEventProcessorAgent extends DynamicCompositeAgent implements EventFeed<EventSubscriptionKey<?>> {

    private final EventFlowManager eventFlowManager;
    private final ConcurrentHashMap<String, Service<?>> registeredServices;
    private final ConcurrentHashMap<String, NamedEventProcessor> registeredEventProcessors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EventSubscriptionKey<?>, EventQueueToEventProcessor> queueProcessorMap = new ConcurrentHashMap<>();
    private final OneToOneConcurrentArrayQueue<Supplier<NamedEventProcessor>> toStartList = new OneToOneConcurrentArrayQueue<>(128);
    private final OneToOneConcurrentArrayQueue<String> toStopList = new OneToOneConcurrentArrayQueue<>(128);
    private final List<EventQueueToEventProcessor> queueReadersToAdd = new ArrayList<>();
    private final MongooseServer mongooseServer;
    private final DeadWheelScheduler scheduler;
    private final Service<SchedulerService> schedulerService;

    public ComposingEventProcessorAgent(String roleName,
                                        EventFlowManager eventFlowManager,
                                        MongooseServer mongooseServer,
                                        DeadWheelScheduler scheduler,
                                        ConcurrentHashMap<String, Service<?>> registeredServices) {
        super(roleName, scheduler);
        this.eventFlowManager = eventFlowManager;
        this.mongooseServer = mongooseServer;
        this.scheduler = scheduler;
        this.registeredServices = registeredServices;
        this.schedulerService = new Service<>(scheduler, SchedulerService.class);
    }

    public void addNamedEventProcessor(Supplier<NamedEventProcessor> initFunction) {
        toStartList.add(initFunction);
    }

    public void removeEventProcessorByName(String name) {
        toStopList.add(name);
    }

    @Override
    public void onStart() {
        // Best-effort core pinning if configured for this agent group (guard for null during unit tests)
        if (mongooseServer != null) {
            Integer coreId = mongooseServer.resolveCoreIdForAgentName(roleName());
            if (coreId != null) {
                com.telamin.mongoose.internal.CoreAffinity.pinCurrentThreadToCore(coreId);
            }
        }
        log.info("onStart " + roleName());
        checkForAdded();
        super.onStart();
    }

    @Override
    public int doWork() throws Exception {
        checkForStopped();
        checkForAdded();
        return super.doWork();
    }

    @Override
    public void onClose() {
        log.info("onClose " + roleName());
        super.onClose();
    }

    @Override
    public void registerSubscriber(StaticEventProcessor subscriber) {
        log.info("registerSubscriber:" + subscriber + " " + roleName());
    }

    @Override
    public void subscribe(StaticEventProcessor subscriber, EventSubscriptionKey<?> subscriptionKey) {
        Objects.requireNonNull(subscriber, "subscriber is null");
        Objects.requireNonNull(subscriptionKey, "subscriptionKey is null");
        log.info("subscribe subscriptionKey:" + subscriptionKey + " subscriber:" + subscriber);

        EventQueueToEventProcessor eventQueueToEventProcessor = queueProcessorMap.get(subscriptionKey);

        if (eventQueueToEventProcessor == null) {
            eventQueueToEventProcessor = eventFlowManager.getMappingAgent(subscriptionKey, this);
            queueProcessorMap.put(subscriptionKey, eventQueueToEventProcessor);
            queueReadersToAdd.add(eventQueueToEventProcessor);
            log.info("added new subscribe subscriptionKey:" + subscriptionKey + " subscriber:" + subscriber);
        }

        eventQueueToEventProcessor.registerProcessor(subscriber);
        eventFlowManager.subscribe(subscriptionKey);
    }

    @Override
    public void unSubscribe(StaticEventProcessor subscriber, EventSubscriptionKey<?> subscriptionKey) {
        if (queueProcessorMap.containsKey(subscriptionKey)) {
            EventQueueToEventProcessor eventQueueToEventProcessor = queueProcessorMap.get(subscriptionKey);
            if (eventQueueToEventProcessor.deregisterProcessor(subscriber) == 0) {
                log.info("EventQueueToEventProcessor listener count = 0, removing subscription:" + subscriptionKey);
                queueProcessorMap.remove(subscriptionKey);
                eventFlowManager.unSubscribe(subscriptionKey);
            }
        }
    }

    @Override
    public void removeAllSubscriptions(StaticEventProcessor subscriber) {
        log.info("removing all subscriptions for:" + subscriber + " " + roleName());
        queueProcessorMap.values().forEach(q -> q.deregisterProcessor(subscriber));
    }

    public Collection<NamedEventProcessor> registeredEventProcessors() {
        return registeredEventProcessors.values();
    }

    private void checkForAdded() {
        if (!toStartList.isEmpty()) {
            toStartList.drain(init -> {
                NamedEventProcessor namedEventProcessor = init.get();
                StaticEventProcessor eventProcessor = namedEventProcessor.eventProcessor();
                registeredEventProcessors.put(namedEventProcessor.name(), namedEventProcessor);
                com.telamin.mongoose.dispatch.ProcessorContext.setCurrentProcessor(eventProcessor);
                eventProcessor.registerService(schedulerService);
                registeredServices.values().forEach(eventProcessor::registerService);
                eventProcessor.addEventFeed(this);
                if (eventProcessor instanceof Lifecycle) {
                    ((Lifecycle) eventProcessor).start();
                    ((Lifecycle) eventProcessor).startComplete();
                }
                com.telamin.mongoose.dispatch.ProcessorContext.removeCurrentProcessor();
            });
        }

        if (!queueReadersToAdd.isEmpty()) {
            if (status() == Status.ACTIVE && tryAdd(queueReadersToAdd.get(0))) {
                queueReadersToAdd.remove(0);
            }
        }
    }

    private void checkForStopped() {
        if (toStopList.isEmpty()) {
            return;
        }
        toStopList.drain(name -> {
            if (registeredEventProcessors.containsKey(name)) {
                var eventProcessor = registeredEventProcessors.remove(name).eventProcessor();
                if (eventProcessor instanceof Lifecycle) {
                    ((Lifecycle) eventProcessor).stop();
                    ((Lifecycle) eventProcessor).tearDown();
                }
            }
        });
    }

    public boolean isProcessorRegistered(String processorName) {
        return registeredEventProcessors.containsKey(processorName);
    }
}
