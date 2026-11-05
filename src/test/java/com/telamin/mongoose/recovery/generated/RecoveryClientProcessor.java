/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 * GENERATED from src/test/fluxtion/recovery-processor.xml.
 * Regenerate with: mvn -o -Pregen process-test-classes
 */
package com.telamin.mongoose.recovery.generated;

import com.telamin.fluxtion.runtime.CloneableDataFlow;
import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.ExportService;
import com.telamin.fluxtion.runtime.annotations.OnEventHandler;
import com.telamin.fluxtion.runtime.audit.Auditor;
import com.telamin.fluxtion.runtime.audit.EventLogControlEvent;
import com.telamin.fluxtion.runtime.audit.EventLogControlEvent.LogLevel;
import com.telamin.fluxtion.runtime.audit.EventLogManager;
import com.telamin.fluxtion.runtime.audit.NodeNameAuditor;
import com.telamin.fluxtion.runtime.callback.CallbackDispatcherImpl;
import com.telamin.fluxtion.runtime.callback.ExportFunctionAuditEvent;
import com.telamin.fluxtion.runtime.callback.InternalEventProcessor;
import com.telamin.fluxtion.runtime.context.DataFlowContext;
import com.telamin.fluxtion.runtime.describe.DescriptorSupport;
import com.telamin.fluxtion.runtime.describe.ProcessorDescriptor;
import com.telamin.fluxtion.runtime.event.Event;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.input.SubscriptionManager;
import com.telamin.fluxtion.runtime.input.SubscriptionManagerNode;
import com.telamin.fluxtion.runtime.lifecycle.BatchHandler;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.node.ForkedTriggerTask;
import com.telamin.fluxtion.runtime.node.MutableDataFlowContext;
import com.telamin.fluxtion.runtime.service.ServiceListener;
import com.telamin.fluxtion.runtime.service.ServiceRegistryNode;
import com.telamin.fluxtion.runtime.time.Clock;
import com.telamin.fluxtion.runtime.time.ClockStrategy.ClockStrategyEvent;
import com.telamin.mongoose.recovery.RecoveryProbeNode;
import com.telamin.mongoose.recovery.RecoveryProbeNode.Input;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 *
 *
 * <pre>
 * generation time           : Not available
 * api version               : 1.1.3
 * analyser version          : 1.0.80
 * target generator version  : 1.0.80
 * </pre>
 *
 * Event classes supported:
 *
 * <ul>
 *   <li>com.telamin.fluxtion.runtime.audit.EventLogControlEvent
 *   <li>com.telamin.fluxtion.runtime.time.ClockStrategy.ClockStrategyEvent
 *   <li>com.telamin.mongoose.recovery.RecoveryProbeNode.Input
 * </ul>
 *
 * @author Greg Higgins
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class RecoveryClientProcessor
    implements CloneableDataFlow<RecoveryClientProcessor>,
        /*--- @ExportService start ---*/
        @ExportService ServiceListener,
        /*--- @ExportService end ---*/
        DataFlow,
        InternalEventProcessor,
        BatchHandler,
        com.telamin.fluxtion.runtime.node.NodeNameLookup {

  //Node declarations
  private final transient CallbackDispatcherImpl callbackDispatcher = new CallbackDispatcherImpl();
  public final transient Clock clock = new Clock();
  public final transient EventLogManager eventLogger = new EventLogManager();
  public final transient NodeNameAuditor nodeNameLookup = new NodeNameAuditor();
  private final transient SubscriptionManagerNode subscriptionManager =
      new SubscriptionManagerNode();
  private final transient MutableDataFlowContext context =
      new com.telamin.fluxtion.runtime.node.MutableDataFlowContext(
          nodeNameLookup, callbackDispatcher, subscriptionManager, callbackDispatcher);;
  public final transient RecoveryProbeNode handler = new RecoveryProbeNode();
  public final transient ServiceRegistryNode serviceRegistry = new ServiceRegistryNode();
  private final transient ExportFunctionAuditEvent functionAudit = new ExportFunctionAuditEvent();
  //Dirty flags
  private boolean initCalled = false;
  private boolean processing = false;
  private boolean buffering = false;
  //M50/W1 - written by CallbackDispatcherImpl when it queues, cleared when it drains empty. Read on
  //the event path instead of walking processor->dispatcher->ArrayDeque to be told the queue is empty.
  //Measured saving on a 3-event-type graph: 0.098ns of a 5.61ns event on a JIT; nothing on native+PGO.
  private boolean callbacksPending = false;

  //Filter constants

  //Self-description of the embeddable surface — see ProcessorDescriptor
  private static final ProcessorDescriptor DESCRIPTOR =
      DescriptorSupport.of(
          RecoveryClientProcessor.class,
          RecoveryClientProcessor::new,
          new ProcessorDescriptor.Input[] {
            new ProcessorDescriptor.Input(
                "Input", "com.telamin.mongoose.recovery.RecoveryProbeNode.Input", false)
          },
          new ProcessorDescriptor.Sink[] {},
          new ProcessorDescriptor.Service[] {},
          new DescriptorSupport.Meta(
              null,
              "1.0.80",
              "508ffe3cb1af34f5603d624be72b5d219ea9ab4f0a983084f7e398d45e9fc3d8",
              null));

  @Override
  public ProcessorDescriptor getDescriptor() {
    return DESCRIPTOR;
  }

  //unknown event handler
  private Consumer unKnownEventHandler = (e) -> {};

  public RecoveryClientProcessor(Map<Object, Object> contextMap) {
    if (context != null) {
      context.replaceMappings(contextMap);
    }
    eventLogger.setClearAfterPublish(false);
    eventLogger.trace = true;
    eventLogger.printEventToString = true;
    eventLogger.printThreadName = true;
    eventLogger.traceLevel = LogLevel.INFO;
    eventLogger.clock = clock;
    eventLogger.binaryRecord = false;
    eventLogger.recordEndTime = true;
    context.setClock(clock);
    serviceRegistry.setDataFlowContext(context);
    handler.setContext(context);
    //node auditors
    initialiseAuditor(clock);
    initialiseAuditor(eventLogger);
    initialiseAuditor(nodeNameLookup);
    initialiseAuditor(serviceRegistry);
    if (subscriptionManager != null) {
      subscriptionManager.setSubscribingEventProcessor(this);
    }
    if (context != null) {
      context.setEventProcessorCallback(this);
    }
  }

  public RecoveryClientProcessor() {
    this(null);
  }

  @Override
  public void init() {
    initCalled = true;
    auditEvent(Lifecycle.LifecycleEvent.Init);
    //initialise dirty lookup map
    isDirty("test");
    clock.init();
    afterEvent();
  }

  @Override
  public void start() {
    if (!initCalled) {
      throw new RuntimeException("init() must be called before start()");
    }
    processing = true;
    try {
      auditEvent(Lifecycle.LifecycleEvent.Start);

      afterEvent();
      callbackDispatcher.dispatchQueuedCallbacks();
    } catch (Throwable t) {
      failedCycle(t);
      throw t;
    } finally {
      processing = false;
    }
  }

  @Override
  public void startComplete() {
    if (!initCalled) {
      throw new RuntimeException("init() must be called before startComplete()");
    }
    processing = true;
    try {
      auditEvent(Lifecycle.LifecycleEvent.StartComplete);

      afterEvent();
      callbackDispatcher.dispatchQueuedCallbacks();
    } catch (Throwable t) {
      failedCycle(t);
      throw t;
    } finally {
      processing = false;
    }
  }

  @Override
  public void stop() {
    if (!initCalled) {
      throw new RuntimeException("init() must be called before stop()");
    }
    processing = true;
    try {
      auditEvent(Lifecycle.LifecycleEvent.Stop);

      afterEvent();
      callbackDispatcher.dispatchQueuedCallbacks();
    } catch (Throwable t) {
      failedCycle(t);
      throw t;
    } finally {
      processing = false;
    }
  }

  @Override
  public void tearDown() {
    initCalled = false;
    auditEvent(Lifecycle.LifecycleEvent.TearDown);
    serviceRegistry.tearDown();
    nodeNameLookup.tearDown();
    eventLogger.tearDown();
    clock.tearDown();
    subscriptionManager.tearDown();
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
    processEvent(event);
  }

  private void processEvent(Object event) {
    if (buffering) {
      triggerCalculation();
    }
    if (processing) {
      callbackDispatcher.queueReentrantEvent(event);
    } else {
      processing = true;
      try {
        onEventInternal(event);
        if (callbacksPending) {
          final boolean sharedBefore = clock.shareReading(true);
          try {
            callbackDispatcher.dispatchQueuedCallbacks();
          } finally {
            clock.shareReading(sharedBefore);
          }
        }
      } catch (Throwable t) {
        failedCycle(t);
        throw t;
      } finally {
        processing = false;
      }
    }
  }

  /**
   * M50/W1 - the dispatcher tells this processor when it has queued work, and when the queue has
   * drained empty. Keeping the answer in a field this processor owns is what lets the event path
   * skip walking into the dispatcher and its ArrayDeque on every event to be told there is nothing
   * to do. The dispatcher owns the WRITE because it sees every queueing path - a node holding the
   * dispatcher directly can queue without this processor ever seeing the call.
   */
  @Override
  public void callbacksPending(boolean pending) {
    callbacksPending = pending;
  }

  @Override
  public void onEventInternal(Object event) {
    if (event instanceof EventLogControlEvent) {
      EventLogControlEvent typedEvent = (EventLogControlEvent) event;
      handleEvent(typedEvent);
    } else if (event instanceof ClockStrategyEvent) {
      ClockStrategyEvent typedEvent = (ClockStrategyEvent) event;
      handleEvent(typedEvent);
    } else if (event instanceof Input) {
      Input typedEvent = (Input) event;
      handleEvent(typedEvent);
    } else {
      unKnownEventHandler(event);
    }
  }

  @OnEventHandler(failBuildIfMissingBooleanReturn = false)
  public void onEvent(EventLogControlEvent event) {
    processEvent(event);
  }

  @OnEventHandler(failBuildIfMissingBooleanReturn = false)
  public void onEvent(ClockStrategyEvent event) {
    processEvent(event);
  }

  @OnEventHandler(failBuildIfMissingBooleanReturn = false)
  public void onEvent(Input event) {
    processEvent(event);
  }

  public void handleEvent(EventLogControlEvent typedEvent) {
    auditEvent(typedEvent);
    //Default, no filter methods
    auditInvocation(
        eventLogger, "eventLogger", "calculationLogConfig", "OnEventHandler", typedEvent);
    eventLogger.calculationLogConfig(typedEvent);
    afterEvent();
  }

  public void handleEvent(ClockStrategyEvent typedEvent) {
    auditEvent(typedEvent);
    //Default, no filter methods
    auditInvocation(clock, "clock", "setClockStrategy", "OnEventHandler", typedEvent);
    clock.setClockStrategy(typedEvent);
    afterEvent();
  }

  public void handleEvent(Input typedEvent) {
    auditEvent(typedEvent);
    //Default, no filter methods
    auditInvocation(handler, "handler", "onInput", "OnEventHandler", typedEvent);
    handler.onInput(typedEvent);
    afterEvent();
  }
  //EVENT DISPATCH - END

  //EXPORTED SERVICE FUNCTIONS - START
  @Override
  public void deRegisterService(com.telamin.fluxtion.runtime.service.Service<?> arg0) {
    beforeServiceCall(
        "@Override\npublic void deRegisterService(com.telamin.fluxtion.runtime.service.Service<?> arg0)");
    ExportFunctionAuditEvent typedEvent = functionAudit;
    try {
      auditInvocation(
          serviceRegistry, "serviceRegistry", "deRegisterService", "ExportService", typedEvent);
      serviceRegistry.deRegisterService(arg0);
      afterServiceCall();
    } catch (Throwable t) {
      failedCycle(t);
      throw t;
    } finally {
      processing = false;
    }
  }

  @Override
  public void registerService(com.telamin.fluxtion.runtime.service.Service<?> arg0) {
    beforeServiceCall(
        "@Override\npublic void registerService(com.telamin.fluxtion.runtime.service.Service<?> arg0)");
    ExportFunctionAuditEvent typedEvent = functionAudit;
    try {
      auditInvocation(
          serviceRegistry, "serviceRegistry", "registerService", "ExportService", typedEvent);
      serviceRegistry.registerService(arg0);
      afterServiceCall();
    } catch (Throwable t) {
      failedCycle(t);
      throw t;
    } finally {
      processing = false;
    }
  }
  //EXPORTED SERVICE FUNCTIONS - END

  //EVENT BUFFERING - START
  public void bufferEvent(Object event) {
    buffering = true;
    try {
      if (event instanceof EventLogControlEvent) {
        EventLogControlEvent typedEvent = (EventLogControlEvent) event;
        auditEvent(typedEvent);
        auditInvocation(
            eventLogger, "eventLogger", "calculationLogConfig", "OnEventHandler", typedEvent);
        eventLogger.calculationLogConfig(typedEvent);
      } else if (event instanceof ClockStrategyEvent) {
        ClockStrategyEvent typedEvent = (ClockStrategyEvent) event;
        auditEvent(typedEvent);
        auditInvocation(clock, "clock", "setClockStrategy", "OnEventHandler", typedEvent);
        clock.setClockStrategy(typedEvent);
      } else if (event instanceof Input) {
        Input typedEvent = (Input) event;
        auditEvent(typedEvent);
        auditInvocation(handler, "handler", "onInput", "OnEventHandler", typedEvent);
        handler.onInput(typedEvent);
      }
    } catch (Throwable t) {
      buffering = false;
      failedCycle(t);
      throw t;
    }
  }

  public void triggerCalculation() {
    buffering = false;
    try {
      String typedEvent = "No event information - buffered dispatch";
      afterEvent();
    } catch (Throwable t) {
      failedCycle(t);
      throw t;
    }
  }
  //EVENT BUFFERING - END

  private void auditEvent(Object typedEvent) {
    clock.eventReceived(typedEvent);
    eventLogger.eventReceived(typedEvent);
  }

  private void auditEvent(Event typedEvent) {
    clock.eventReceived(typedEvent);
    eventLogger.eventReceived(typedEvent);
  }

  private void auditInvocation(
      Object node, String nodeName, String methodName, String annotation, Object typedEvent) {
    eventLogger.nodeInvoked(node, nodeName, methodName, annotation, typedEvent);
  }

  private void initialiseAuditor(Auditor auditor) {
    auditor.init();
    auditor.nodeRegistered(callbackDispatcher, "callbackDispatcher");
    auditor.nodeRegistered(subscriptionManager, "subscriptionManager");
    auditor.nodeRegistered(context, "context");
    auditor.nodeRegistered(handler, "handler");
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

  /**
   * DataFlow.runInEventCycle: run a host's action as an event cycle, with auditEvent as its audit
   * context. A buffered calculation runs first (it closes its own record), then the cycle opens
   * with the caller's event and the action runs; then the cycle is closed and what the action
   * queued is dispatched. A failed action's cycle is still closed and its queued work is not
   * dispatched; a throw while closing or draining fails the cycle (failedCycle). The action's own
   * exception is the one the caller sees, and a finally clears processing. auditEvent is dispatched
   * to no node and marks nothing dirty. An auditEvent that is an Event supplies its own event time,
   * as on the event path; any other object takes the process time. Not re-entrant. To disable the
   * path in a generated processor, make this throw.
   *
   * <p>No @Override, deliberately: generated source must also compile against a runtime that
   * predates DataFlow.runInEventCycle (1.0.16), where this is an ordinary public method a host
   * finds on the class. From the runtime that declares it, it overrides the interface default by
   * signature. GeneratedSourceOnOldRuntimeTest compiles freshly generated source against the oldest
   * supported runtime so this cannot silently regress.
   */
  public void runInEventCycle(Object auditEvent, Runnable action) {
    if (processing) {
      throw new IllegalStateException(
          "runInEventCycle is not re-entrant: it was called inside an event cycle");
    }
    if (buffering) {
      triggerCalculation();
    }
    processing = true;
    try {
      try {
        // an Event supplies its own event time, as on the event path: the static type selects the auditors'
        // overload, eventReceived(Event) or eventReceived(Object)
        if (auditEvent instanceof com.telamin.fluxtion.runtime.event.Event) {
          auditEvent((com.telamin.fluxtion.runtime.event.Event) auditEvent);
        } else {
          auditEvent(auditEvent);
        }
        action.run();
      } catch (Throwable t) {
        // the action failed: the cycle is still closed, as a host's own audit bracket closes its record, and
        // what it queued is not dispatched. A throw from the close is kept as suppressed evidence.
        callbackDispatcher.discardQueuedCallbacks();
        try {
          afterEvent();
        } catch (Throwable closeFailure) {
          if (closeFailure != t) {
            t.addSuppressed(closeFailure);
          }
          failedCycle(t);
        }
        // the close runs @AfterEvent code, which can queue work: discarded too, never dispatched later
        callbackDispatcher.discardQueuedCallbacks();
        throw t;
      }
      // #105: closing the cycle and draining what the action queued are part of the cycle, so a throw there
      // fails it and the failing queued item is not left at the head of the queue for the next event
      try {
        afterEvent();
        callbackDispatcher.dispatchQueuedCallbacks();
      } catch (Throwable t) {
        failedCycle(t);
        throw t;
      }
    } finally {
      processing = false;
    }
  }

  private void afterEvent() {
    clock.processingComplete();
    eventLogger.processingComplete();
  }

  /**
   * The close of a cycle that failed (telaminai/fluxtion-compiler#105): framework state only, no
   * user code. Dirty flags and fork tasks are reset so nothing of the failed cycle triggers a later
   * one, the auditors close its record so the next cycle's record is its own, and, on a runtime
   * that declares discardQueuedCallbacks (1.1.3+), what the cycle queued is discarded. An auditor's
   * throw is added to the cycle's exception as suppressed.
   */
  private void failedCycle(Throwable cause) {

    try {
      clock.processingComplete();
      eventLogger.processingComplete();
    } catch (Throwable t) {
      if (t != cause) {
        cause.addSuppressed(t);
      }
    }
    // last, so nothing the close itself queued survives it
    callbackDispatcher.discardQueuedCallbacks();
  }

  @Override
  public void batchPause() {
    processing = true;
    try {
      auditEvent(Lifecycle.LifecycleEvent.BatchPause);

      afterEvent();
      callbackDispatcher.dispatchQueuedCallbacks();
    } catch (Throwable t) {
      failedCycle(t);
      throw t;
    } finally {
      processing = false;
    }
  }

  @Override
  public void batchEnd() {
    processing = true;
    try {
      auditEvent(Lifecycle.LifecycleEvent.BatchEnd);

      afterEvent();
      callbackDispatcher.dispatchQueuedCallbacks();
    } catch (Throwable t) {
      failedCycle(t);
      throw t;
    } finally {
      processing = false;
    }
  }

  @Override
  public boolean isDirty(Object node) {
    return dirtySupplier(node).getAsBoolean();
  }

  @Override
  public BooleanSupplier dirtySupplier(Object node) {
    return DataFlow.ALWAYS_FALSE;
  }

  @Override
  public void setDirty(Object node, boolean dirtyFlag) {
    //dirty filtering disabled - no flags to set
  }

  /**
   * M50/W4 — nodes resolved by a generated switch, not by a populated map: registering them would
   * publish every node into the auditor's HashMaps and stop the graph being dissolved.
   */
  @SuppressWarnings("unchecked")
  @Override
  public <T> T getInstanceById(String id) throws NoSuchFieldException {
    switch (id) {
      case "eventLogger":
        return (T) eventLogger;
      case "nodeNameLookup":
        return (T) nodeNameLookup;
      case "callbackDispatcher":
        return (T) callbackDispatcher;
      case "subscriptionManager":
        return (T) subscriptionManager;
      case "context":
        return (T) context;
      case "serviceRegistry":
        return (T) serviceRegistry;
      case "clock":
        return (T) clock;
      case "handler":
        return (T) handler;
      default:
        throw new NoSuchFieldException(id);
    }
  }

  /** M50/W4 — the reverse direction, also generated. */
  @Override
  public String lookupInstanceName(Object node) {
    if (node == eventLogger) {
      return "eventLogger";
    }
    if (node == nodeNameLookup) {
      return "nodeNameLookup";
    }
    if (node == callbackDispatcher) {
      return "callbackDispatcher";
    }
    if (node == subscriptionManager) {
      return "subscriptionManager";
    }
    if (node == context) {
      return "context";
    }
    if (node == serviceRegistry) {
      return "serviceRegistry";
    }
    if (node == clock) {
      return "clock";
    }
    if (node == handler) {
      return "handler";
    }
    return null;
  }

  @Override
  public <T> T getNodeById(String id) throws NoSuchFieldException {
    try {
      return getInstanceById(id);
    } catch (NoSuchFieldException miss) {
      // Auditors live on the SEP as fields rather than in nodeNameLookup, so callers
      // (especially DataFlow.getServiceById) get one unified lookup path. The auditor
      // half is a generated switch, not a reflective probe: reflection here would
      // require native-image reflection configuration from every user, and would fail
      // at runtime rather than at build time.
      try {
        @SuppressWarnings("unchecked")
        T t = (T) getAuditorById(id);
        return t;
      } catch (NoSuchFieldException stillMissing) {
        throw miss;
      }
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <A extends Auditor> A getAuditorById(String id) throws NoSuchFieldException {
    switch (id) {
      case "clock":
        return (A) clock;
      case "eventLogger":
        return (A) eventLogger;
      case "nodeNameLookup":
        return (A) nodeNameLookup;
      case "serviceRegistry":
        return (A) serviceRegistry;
      default:
        throw new NoSuchFieldException(id);
    }
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
  public RecoveryClientProcessor newInstance() {
    return new RecoveryClientProcessor();
  }

  @Override
  public RecoveryClientProcessor newInstance(Map<Object, Object> contextMap) {
    return new RecoveryClientProcessor();
  }

  @Override
  public String getLastAuditLogRecord() {
    try {
      EventLogManager eventLogManager = getAuditorById(EventLogManager.NODE_NAME);
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
