/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/*
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the Server Side Public License, version 1,
* as published by MongoDB, Inc.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* Server Side License for more details.
*
* You should have received a copy of the Server Side Public License
* along with this program.  If not, see
*
<http://www.mongodb.com/licensing/server-side-public-license>.
*/
package com.telamin.mongoose.example;

import com.fluxtion.runtime.EventProcessor;
import com.fluxtion.runtime.StaticEventProcessor;
import com.fluxtion.runtime.audit.Auditor;
import com.fluxtion.runtime.audit.EventLogManager;
import com.fluxtion.runtime.audit.NodeNameAuditor;
import com.fluxtion.runtime.callback.CallbackDispatcherImpl;
import com.fluxtion.runtime.callback.ExportFunctionAuditEvent;
import com.fluxtion.runtime.callback.InternalEventProcessor;
import com.fluxtion.runtime.dataflow.function.MapFlowFunction.MapRef2RefFlowFunction;
import com.fluxtion.runtime.dataflow.function.PeekFlowFunction;
import com.fluxtion.runtime.dataflow.helpers.Peekers.TemplateMessage;
import com.fluxtion.runtime.event.Event;
import com.fluxtion.runtime.event.NamedFeedEvent;
import com.fluxtion.runtime.input.EventFeed;
import com.fluxtion.runtime.input.SubscriptionManager;
import com.fluxtion.runtime.input.SubscriptionManagerNode;
import com.fluxtion.runtime.lifecycle.BatchHandler;
import com.fluxtion.runtime.lifecycle.Lifecycle;
import com.fluxtion.runtime.node.MutableEventProcessorContext;
import com.fluxtion.runtime.node.NamedFeedEventHandlerNode;
import com.fluxtion.runtime.service.ServiceListener;
import com.fluxtion.runtime.service.ServiceRegistryNode;
import com.fluxtion.runtime.time.Clock;
import com.fluxtion.runtime.time.ClockStrategy.ClockStrategyEvent;
import com.telamin.mongoose.test.HeartbeatEvent;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * <pre>
 * generation time                 : Not available
 * eventProcessorGenerator version : 9.5.0
 * api version                     : 9.5.0
 * </pre>
 * <p>
 * Event classes supported:
 *
 * <ul>
 *   <li>com.fluxtion.compiler.generation.model.ExportFunctionMarker
 *   <li>com.fluxtion.example.server.heartbeater.HeartbeatEvent
 *   <li>com.fluxtion.runtime.time.ClockStrategy.ClockStrategyEvent
 *   <li>com.fluxtion.runtime.event.NamedFeedEvent
 * </ul>
 *
 * @author Greg Higgins
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class HeartBeatExampleProcessor
        implements EventProcessor<HeartBeatExampleProcessor>,
        /*--- @ExportService start ---*/
        ServiceListener,
        /*--- @ExportService end ---*/
        StaticEventProcessor,
        InternalEventProcessor,
        BatchHandler,
        Lifecycle {

    //Node declarations
    private final CallbackDispatcherImpl callbackDispatcher = new CallbackDispatcherImpl();
    public final Clock clock = new Clock();
    private final NamedFeedEventHandlerNode eventFeedHandler_heartBeater =
            new NamedFeedEventHandlerNode<>("heartBeater", "eventFeedHandler_heartBeater");
    public final NodeNameAuditor nodeNameLookup = new NodeNameAuditor();
    private final SubscriptionManagerNode subscriptionManager = new SubscriptionManagerNode();
    private final MutableEventProcessorContext context =
            new MutableEventProcessorContext(
                    nodeNameLookup, callbackDispatcher, subscriptionManager, callbackDispatcher);
    private final MapRef2RefFlowFunction mapRef2RefFlowFunction_1 =
            new MapRef2RefFlowFunction<>(eventFeedHandler_heartBeater, NamedFeedEvent<Object>::data);
    public final ServiceRegistryNode serviceRegistry = new ServiceRegistryNode();
    private final TemplateMessage templateMessage_2 = new TemplateMessage<>("received heartbeat:{}");
    private final PeekFlowFunction peekFlowFunction_3 =
            new PeekFlowFunction<>(mapRef2RefFlowFunction_1, templateMessage_2::templateAndLogToConsole);
    private final HeartBeatNode heartBeatNode_0 = new HeartBeatNode();
    private final ExportFunctionAuditEvent functionAudit = new ExportFunctionAuditEvent();
    //Dirty flags
    private boolean initCalled = false;
    private boolean processing = false;
    private boolean buffering = false;
    private final IdentityHashMap<Object, BooleanSupplier> dirtyFlagSupplierMap =
            new IdentityHashMap<>(3);
    private final IdentityHashMap<Object, Consumer<Boolean>> dirtyFlagUpdateMap =
            new IdentityHashMap<>(3);

    private boolean isDirty_clock = false;
    private boolean isDirty_eventFeedHandler_heartBeater = false;
    private boolean isDirty_mapRef2RefFlowFunction_1 = false;

    //Forked declarations

    //Filter constants

    //unknown event handler
    private Consumer unKnownEventHandler = (e) -> {};

    public HeartBeatExampleProcessor(Map<Object, Object> contextMap) {
        if (context != null) {
            context.replaceMappings(contextMap);
        }
        mapRef2RefFlowFunction_1.setEventProcessorContext(context);
        peekFlowFunction_3.setEventProcessorContext(context);
        templateMessage_2.clock = clock;
        context.setClock(clock);
        serviceRegistry.setEventProcessorContext(context);
        //node auditors
        initialiseAuditor(clock);
        initialiseAuditor(nodeNameLookup);
        initialiseAuditor(serviceRegistry);
        if (subscriptionManager != null) {
            subscriptionManager.setSubscribingEventProcessor(this);
        }
        if (context != null) {
            context.setEventProcessorCallback(this);
        }
    }

    public HeartBeatExampleProcessor() {
        this(null);
    }

    @Override
    public void init() {
        initCalled = true;
        auditEvent(LifecycleEvent.Init);
        //initialise dirty lookup map
        isDirty("test");
        clock.init();
        eventFeedHandler_heartBeater.init();
        mapRef2RefFlowFunction_1.initialiseEventStream();
        templateMessage_2.initialise();
        peekFlowFunction_3.initialiseEventStream();
        afterEvent();
    }

    @Override
    public void start() {
        if (!initCalled) {
            throw new RuntimeException("init() must be called before start()");
        }
        processing = true;
        auditEvent(LifecycleEvent.Start);
        eventFeedHandler_heartBeater.start();
        templateMessage_2.start();
        afterEvent();
        callbackDispatcher.dispatchQueuedCallbacks();
        processing = false;
    }

    @Override
    public void startComplete() {
        if (!initCalled) {
            throw new RuntimeException("init() must be called before startComplete()");
        }
        processing = true;
        auditEvent(LifecycleEvent.StartComplete);

        afterEvent();
        callbackDispatcher.dispatchQueuedCallbacks();
        processing = false;
    }

    @Override
    public void stop() {
        if (!initCalled) {
            throw new RuntimeException("init() must be called before stop()");
        }
        processing = true;
        auditEvent(LifecycleEvent.Stop);
        eventFeedHandler_heartBeater.stop();
        afterEvent();
        callbackDispatcher.dispatchQueuedCallbacks();
        processing = false;
    }

    @Override
    public void tearDown() {
        initCalled = false;
        auditEvent(LifecycleEvent.TearDown);
        serviceRegistry.tearDown();
        nodeNameLookup.tearDown();
        clock.tearDown();
        subscriptionManager.tearDown();
        eventFeedHandler_heartBeater.tearDown();
        afterEvent();
    }

    @Override
    public void setContextParameterMap(Map<Object, Object> newContextMapping) {
        context.replaceMappings(newContextMapping);
    }

    @Override
    public void addContextParameter(Object key, Object value) {
        context.addMapping(key, value);
    }

    //EVENT DISPATCH - START
    @Override
    public void onEvent(Object event) {
        if (buffering) {
            triggerCalculation();
        }
        if (processing) {
            callbackDispatcher.queueReentrantEvent(event);
        } else {
            processing = true;
            onEventInternal(event);
            callbackDispatcher.dispatchQueuedCallbacks();
            processing = false;
        }
    }

    @Override
    public void onEventInternal(Object event) {
        if (event instanceof HeartbeatEvent) {
            HeartbeatEvent typedEvent = (HeartbeatEvent) event;
            handleEvent(typedEvent);
        } else if (event instanceof ClockStrategyEvent) {
            ClockStrategyEvent typedEvent = (ClockStrategyEvent) event;
            handleEvent(typedEvent);
        } else if (event instanceof NamedFeedEvent) {
            NamedFeedEvent typedEvent = (NamedFeedEvent) event;
            handleEvent(typedEvent);
        } else {
            unKnownEventHandler(event);
        }
    }

    public void handleEvent(HeartbeatEvent typedEvent) {
        auditEvent(typedEvent);
        //Default, no filter methods
        heartBeatNode_0.heartBeat(typedEvent);
        afterEvent();
    }

    public void handleEvent(ClockStrategyEvent typedEvent) {
        auditEvent(typedEvent);
        //Default, no filter methods
        isDirty_clock = true;
        clock.setClockStrategy(typedEvent);
        afterEvent();
    }

    public void handleEvent(NamedFeedEvent typedEvent) {
        auditEvent(typedEvent);
        switch (typedEvent.filterString()) {
            //Event Class:[com.fluxtion.runtime.event.NamedFeedEvent] filterString:[heartBeater]
            case ("heartBeater"):
                handle_NamedFeedEvent_heartBeater(typedEvent);
                afterEvent();
                return;
        }
        afterEvent();
    }
    //EVENT DISPATCH - END

    //FILTERED DISPATCH - START
    private void handle_NamedFeedEvent_heartBeater(NamedFeedEvent typedEvent) {
        isDirty_eventFeedHandler_heartBeater = eventFeedHandler_heartBeater.onEvent(typedEvent);
        if (isDirty_eventFeedHandler_heartBeater) {
            mapRef2RefFlowFunction_1.inputUpdated(eventFeedHandler_heartBeater);
        }
        if (guardCheck_mapRef2RefFlowFunction_1()) {
            isDirty_mapRef2RefFlowFunction_1 = mapRef2RefFlowFunction_1.map();
            if (isDirty_mapRef2RefFlowFunction_1) {
                peekFlowFunction_3.inputUpdated(mapRef2RefFlowFunction_1);
            }
        }
        if (guardCheck_peekFlowFunction_3()) {
            peekFlowFunction_3.peek();
        }
    }
    //FILTERED DISPATCH - END

    //EXPORTED SERVICE FUNCTIONS - START
    @Override
    public void deRegisterService(com.fluxtion.runtime.service.Service<?> arg0) {
        beforeServiceCall(
                "public void com.fluxtion.runtime.service.ServiceRegistryNode.deRegisterService(com.fluxtion.runtime.service.Service<?>)");
        ExportFunctionAuditEvent typedEvent = functionAudit;
        serviceRegistry.deRegisterService(arg0);
        afterServiceCall();
    }

    @Override
    public void registerService(com.fluxtion.runtime.service.Service<?> arg0) {
        beforeServiceCall(
                "public void com.fluxtion.runtime.service.ServiceRegistryNode.registerService(com.fluxtion.runtime.service.Service<?>)");
        ExportFunctionAuditEvent typedEvent = functionAudit;
        serviceRegistry.registerService(arg0);
        afterServiceCall();
    }
    //EXPORTED SERVICE FUNCTIONS - END

    //EVENT BUFFERING - START
    public void bufferEvent(Object event) {
        buffering = true;
        if (event instanceof HeartbeatEvent) {
            HeartbeatEvent typedEvent = (HeartbeatEvent) event;
            auditEvent(typedEvent);
            heartBeatNode_0.heartBeat(typedEvent);
        } else if (event instanceof ClockStrategyEvent) {
            ClockStrategyEvent typedEvent = (ClockStrategyEvent) event;
            auditEvent(typedEvent);
            isDirty_clock = true;
            clock.setClockStrategy(typedEvent);
        } else if (event instanceof NamedFeedEvent) {
            NamedFeedEvent typedEvent = (NamedFeedEvent) event;
            auditEvent(typedEvent);
            switch (typedEvent.filterString()) {
                //Event Class:[com.fluxtion.runtime.event.NamedFeedEvent] filterString:[heartBeater]
                case ("heartBeater"):
                    handle_NamedFeedEvent_heartBeater_bufferDispatch(typedEvent);
                    afterEvent();
                    return;
            }
        }
    }

    private void handle_NamedFeedEvent_heartBeater_bufferDispatch(NamedFeedEvent typedEvent) {
        isDirty_eventFeedHandler_heartBeater = eventFeedHandler_heartBeater.onEvent(typedEvent);
        if (isDirty_eventFeedHandler_heartBeater) {
            mapRef2RefFlowFunction_1.inputUpdated(eventFeedHandler_heartBeater);
        }
    }

    public void triggerCalculation() {
        buffering = false;
        String typedEvent = "No event information - buffered dispatch";
        if (guardCheck_mapRef2RefFlowFunction_1()) {
            isDirty_mapRef2RefFlowFunction_1 = mapRef2RefFlowFunction_1.map();
            if (isDirty_mapRef2RefFlowFunction_1) {
                peekFlowFunction_3.inputUpdated(mapRef2RefFlowFunction_1);
            }
        }
        if (guardCheck_peekFlowFunction_3()) {
            peekFlowFunction_3.peek();
        }
        afterEvent();
    }
    //EVENT BUFFERING - END

    private void auditEvent(Object typedEvent) {
        clock.eventReceived(typedEvent);
        nodeNameLookup.eventReceived(typedEvent);
        serviceRegistry.eventReceived(typedEvent);
    }

    private void auditEvent(Event typedEvent) {
        clock.eventReceived(typedEvent);
        nodeNameLookup.eventReceived(typedEvent);
        serviceRegistry.eventReceived(typedEvent);
    }

    private void initialiseAuditor(Auditor auditor) {
        auditor.init();
        auditor.nodeRegistered(heartBeatNode_0, "heartBeatNode_0");
        auditor.nodeRegistered(callbackDispatcher, "callbackDispatcher");
        auditor.nodeRegistered(mapRef2RefFlowFunction_1, "mapRef2RefFlowFunction_1");
        auditor.nodeRegistered(peekFlowFunction_3, "peekFlowFunction_3");
        auditor.nodeRegistered(templateMessage_2, "templateMessage_2");
        auditor.nodeRegistered(subscriptionManager, "subscriptionManager");
        auditor.nodeRegistered(context, "context");
        auditor.nodeRegistered(eventFeedHandler_heartBeater, "eventFeedHandler_heartBeater");
    }

    private void beforeServiceCall(String functionDescription) {
        functionAudit.setFunctionDescription(functionDescription);
        auditEvent(functionAudit);
        if (buffering) {
            triggerCalculation();
        }
        processing = true;
    }

    private void afterServiceCall() {
        afterEvent();
        callbackDispatcher.dispatchQueuedCallbacks();
        processing = false;
    }

    private void afterEvent() {

        clock.processingComplete();
        nodeNameLookup.processingComplete();
        serviceRegistry.processingComplete();
        isDirty_clock = false;
        isDirty_eventFeedHandler_heartBeater = false;
        isDirty_mapRef2RefFlowFunction_1 = false;
    }

    @Override
    public void batchPause() {
        auditEvent(LifecycleEvent.BatchPause);
        processing = true;

        afterEvent();
        callbackDispatcher.dispatchQueuedCallbacks();
        processing = false;
    }

    @Override
    public void batchEnd() {
        auditEvent(LifecycleEvent.BatchEnd);
        processing = true;

        afterEvent();
        callbackDispatcher.dispatchQueuedCallbacks();
        processing = false;
    }

    @Override
    public boolean isDirty(Object node) {
        return dirtySupplier(node).getAsBoolean();
    }

    @Override
    public BooleanSupplier dirtySupplier(Object node) {
        if (dirtyFlagSupplierMap.isEmpty()) {
            dirtyFlagSupplierMap.put(clock, () -> isDirty_clock);
            dirtyFlagSupplierMap.put(
                    eventFeedHandler_heartBeater, () -> isDirty_eventFeedHandler_heartBeater);
            dirtyFlagSupplierMap.put(mapRef2RefFlowFunction_1, () -> isDirty_mapRef2RefFlowFunction_1);
        }
        return dirtyFlagSupplierMap.getOrDefault(node, StaticEventProcessor.ALWAYS_FALSE);
    }

    @Override
    public void setDirty(Object node, boolean dirtyFlag) {
        if (dirtyFlagUpdateMap.isEmpty()) {
            dirtyFlagUpdateMap.put(clock, (b) -> isDirty_clock = b);
            dirtyFlagUpdateMap.put(
                    eventFeedHandler_heartBeater, (b) -> isDirty_eventFeedHandler_heartBeater = b);
            dirtyFlagUpdateMap.put(mapRef2RefFlowFunction_1, (b) -> isDirty_mapRef2RefFlowFunction_1 = b);
        }
        dirtyFlagUpdateMap.get(node).accept(dirtyFlag);
    }

    private boolean guardCheck_mapRef2RefFlowFunction_1() {
        return isDirty_eventFeedHandler_heartBeater;
    }

    private boolean guardCheck_peekFlowFunction_3() {
        return isDirty_mapRef2RefFlowFunction_1;
    }

    private boolean guardCheck_context() {
        return isDirty_clock;
    }

    @Override
    public <T> T getNodeById(String id) throws NoSuchFieldException {
        return nodeNameLookup.getInstanceById(id);
    }

    @Override
    public <A extends Auditor> A getAuditorById(String id)
            throws NoSuchFieldException, IllegalAccessException {
        return (A) this.getClass().getField(id).get(this);
    }

    @Override
    public void addEventFeed(EventFeed eventProcessorFeed) {
        subscriptionManager.addEventProcessorFeed(eventProcessorFeed);
    }

    @Override
    public void removeEventFeed(EventFeed eventProcessorFeed) {
        subscriptionManager.removeEventProcessorFeed(eventProcessorFeed);
    }

    @Override
    public HeartBeatExampleProcessor newInstance() {
        return new HeartBeatExampleProcessor();
    }

    @Override
    public HeartBeatExampleProcessor newInstance(Map<Object, Object> contextMap) {
        return new HeartBeatExampleProcessor();
    }

    @Override
    public String getLastAuditLogRecord() {
        try {
            EventLogManager eventLogManager =
                    (EventLogManager) this.getClass().getField(EventLogManager.NODE_NAME).get(this);
            return eventLogManager.lastRecordAsString();
        } catch (Throwable e) {
            return "";
        }
    }

    public void unKnownEventHandler(Object object) {
        unKnownEventHandler.accept(object);
    }

    @Override
    public <T> void setUnKnownEventHandler(Consumer<T> consumer) {
        unKnownEventHandler = consumer;
    }

    @Override
    public SubscriptionManager getSubscriptionManager() {
        return subscriptionManager;
    }
}
