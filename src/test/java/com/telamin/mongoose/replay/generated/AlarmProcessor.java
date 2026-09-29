package com.telamin.mongoose.replay.generated;

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
import com.telamin.fluxtion.runtime.event.Signal;
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
import com.telamin.mongoose.replay.generated.AlarmNodes.AlarmMonitor;
import com.telamin.mongoose.replay.generated.AlarmNodes.AlarmPublisher;
import com.telamin.mongoose.replay.generated.AlarmNodes.Reading;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 *
 *
 * <pre>
 * generation time           : Not available
 * api version               : 1.0.16
 * analyser version          : 1.0.71
 * target generator version  : 1.0.75
 * </pre>
 *
 * Event classes supported:
 *
 * <ul>
 *   <li>com.telamin.fluxtion.runtime.audit.EventLogControlEvent
 *   <li>com.telamin.fluxtion.runtime.event.Signal
 *   <li>com.telamin.fluxtion.runtime.time.ClockStrategy.ClockStrategyEvent
 *   <li>com.telamin.mongoose.replay.generated.AlarmNodes.Reading
 * </ul>
 *
 * @author Greg Higgins
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class AlarmProcessor
    implements CloneableDataFlow<AlarmProcessor>,
        /*--- @ExportService start ---*/
        @ExportService ServiceListener,
        /*--- @ExportService end ---*/
        DataFlow,
        InternalEventProcessor,
        BatchHandler,
        com.telamin.fluxtion.runtime.node.NodeNameLookup {

  //Node declarations
  public final transient AlarmMonitor alarmMonitor = new AlarmMonitor();
  public final transient AlarmPublisher alarmPublisher = new AlarmPublisher();
  private final transient CallbackDispatcherImpl callbackDispatcher = new CallbackDispatcherImpl();
  public final transient Clock clock = new Clock();
  public final transient EventLogManager eventLogger = new EventLogManager();
  public final transient NodeNameAuditor nodeNameLookup = new NodeNameAuditor();
  private final transient SubscriptionManagerNode subscriptionManager =
      new SubscriptionManagerNode();
  private final transient MutableDataFlowContext context =
      new com.telamin.fluxtion.runtime.node.MutableDataFlowContext(
          nodeNameLookup, callbackDispatcher, subscriptionManager, callbackDispatcher);;
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
  private final transient IdentityHashMap<Object, BooleanSupplier> dirtyFlagSupplierMap =
      new IdentityHashMap<>(1);
  private final transient IdentityHashMap<Object, Consumer<Boolean>> dirtyFlagUpdateMap =
      new IdentityHashMap<>(1);

  private boolean isDirty_alarmMonitor = false;

  //Forked declarations

  //Filter constants

  //Self-description of the embeddable surface — see ProcessorDescriptor
  private static final ProcessorDescriptor DESCRIPTOR =
      DescriptorSupport.of(
          AlarmProcessor.class,
          AlarmProcessor::new,
          new ProcessorDescriptor.Input[] {
            new ProcessorDescriptor.Input(
                "Reading", "com.telamin.mongoose.replay.generated.AlarmNodes.Reading", false),
            new ProcessorDescriptor.Input(
                "admin:alarm.reset", "com.telamin.fluxtion.runtime.event.Signal", true)
          },
          new ProcessorDescriptor.Sink[] {},
          new ProcessorDescriptor.Service[] {},
          new DescriptorSupport.Meta(
              null,
              "1.0.71",
              "ef90a3a6d0ddd03608768ac65105260410841401f6d6b69516d12a28ac64f92b",
              null));

  @Override
  public ProcessorDescriptor getDescriptor() {
    return DESCRIPTOR;
  }

  //unknown event handler
  private Consumer unKnownEventHandler = (e) -> {};

  public AlarmProcessor(Map<Object, Object> contextMap) {
    if (context != null) {
      context.replaceMappings(contextMap);
    }
    eventLogger.setClearAfterPublish(false);
    eventLogger.trace = false;
    eventLogger.printEventToString = true;
    eventLogger.printThreadName = true;
    eventLogger.traceLevel = LogLevel.NONE;
    eventLogger.clock = clock;
    eventLogger.binaryRecord = false;
    eventLogger.recordEndTime = true;
    context.setClock(clock);
    serviceRegistry.setDataFlowContext(context);
    alarmMonitor.limit = 10.0;
    alarmPublisher.monitor = alarmMonitor;
    alarmPublisher.changes = 0;
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

  public AlarmProcessor() {
    this(null);
  }

  @Override
  public void init() {
    initCalled = true;
    auditEvent(Lifecycle.LifecycleEvent.Init);
    //initialise dirty lookup map
    isDirty("test");
    alarmMonitor.init();
    alarmPublisher.init();
    clock.init();
    afterEvent();
  }

  @Override
  public void start() {
    if (!initCalled) {
      throw new RuntimeException("init() must be called before start()");
    }
    processing = true;
    auditEvent(Lifecycle.LifecycleEvent.Start);
    alarmMonitor.start();
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
    auditEvent(Lifecycle.LifecycleEvent.StartComplete);

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
    auditEvent(Lifecycle.LifecycleEvent.Stop);

    afterEvent();
    callbackDispatcher.dispatchQueuedCallbacks();
    processing = false;
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
      onEventInternal(event);
      if (callbacksPending) {
        final boolean sharedBefore = clock.shareReading(true);
        callbackDispatcher.dispatchQueuedCallbacks();
        clock.shareReading(sharedBefore);
      }
      processing = false;
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
    } else if (event instanceof Signal) {
      Signal typedEvent = (Signal) event;
      handleEvent(typedEvent);
    } else if (event instanceof ClockStrategyEvent) {
      ClockStrategyEvent typedEvent = (ClockStrategyEvent) event;
      handleEvent(typedEvent);
    } else if (event instanceof Reading) {
      Reading typedEvent = (Reading) event;
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
  public void onEvent(Signal event) {
    processEvent(event);
  }

  @OnEventHandler(failBuildIfMissingBooleanReturn = false)
  public void onEvent(ClockStrategyEvent event) {
    processEvent(event);
  }

  @OnEventHandler(failBuildIfMissingBooleanReturn = false)
  public void onEvent(Reading event) {
    processEvent(event);
  }

  public void handleEvent(EventLogControlEvent typedEvent) {
    auditEvent(typedEvent);
    //Default, no filter methods
    eventLogger.calculationLogConfig(typedEvent);
    afterEvent();
  }

  public void handleEvent(Signal typedEvent) {
    auditEvent(typedEvent);
    switch (typedEvent.filterString()) {
      case ("admin:alarm.reset"):
        handle_Signal_admin_alarm_reset(typedEvent);
        afterEvent();
        return;
    }
    afterEvent();
  }

  public void handleEvent(ClockStrategyEvent typedEvent) {
    auditEvent(typedEvent);
    //Default, no filter methods
    clock.setClockStrategy(typedEvent);
    afterEvent();
  }

  public void handleEvent(Reading typedEvent) {
    auditEvent(typedEvent);
    //Default, no filter methods
    isDirty_alarmMonitor = alarmMonitor.onReading(typedEvent);
    if (guardCheck_alarmPublisher()) {
      alarmPublisher.onChange();
    }
    afterEvent();
  }
  //EVENT DISPATCH - END

  //FILTERED DISPATCH - START
  private void handle_Signal_admin_alarm_reset(Signal typedEvent) {
    isDirty_alarmMonitor = alarmMonitor.reset(typedEvent);
    if (guardCheck_alarmPublisher()) {
      alarmPublisher.onChange();
    }
  }
  //FILTERED DISPATCH - END

  //EXPORTED SERVICE FUNCTIONS - START
  @Override
  public void deRegisterService(com.telamin.fluxtion.runtime.service.Service<?> arg0) {
    beforeServiceCall(
        "@Override\npublic void deRegisterService(com.telamin.fluxtion.runtime.service.Service<?> arg0)");
    ExportFunctionAuditEvent typedEvent = functionAudit;
    serviceRegistry.deRegisterService(arg0);
    afterServiceCall();
  }

  @Override
  public void registerService(com.telamin.fluxtion.runtime.service.Service<?> arg0) {
    beforeServiceCall(
        "@Override\npublic void registerService(com.telamin.fluxtion.runtime.service.Service<?> arg0)");
    ExportFunctionAuditEvent typedEvent = functionAudit;
    serviceRegistry.registerService(arg0);
    afterServiceCall();
  }
  //EXPORTED SERVICE FUNCTIONS - END

  //EVENT BUFFERING - START
  public void bufferEvent(Object event) {
    buffering = true;
    if (event instanceof EventLogControlEvent) {
      EventLogControlEvent typedEvent = (EventLogControlEvent) event;
      auditEvent(typedEvent);
      eventLogger.calculationLogConfig(typedEvent);
    } else if (event instanceof Signal) {
      Signal typedEvent = (Signal) event;
      auditEvent(typedEvent);
      switch (typedEvent.filterString()) {
        case ("admin:alarm.reset"):
          handle_Signal_admin_alarm_reset_bufferDispatch(typedEvent);
          afterEvent();
          return;
      }
    } else if (event instanceof ClockStrategyEvent) {
      ClockStrategyEvent typedEvent = (ClockStrategyEvent) event;
      auditEvent(typedEvent);
      clock.setClockStrategy(typedEvent);
    } else if (event instanceof Reading) {
      Reading typedEvent = (Reading) event;
      auditEvent(typedEvent);
      isDirty_alarmMonitor = alarmMonitor.onReading(typedEvent);
    }
  }

  private void handle_Signal_admin_alarm_reset_bufferDispatch(Signal typedEvent) {
    isDirty_alarmMonitor = alarmMonitor.reset(typedEvent);
  }

  public void triggerCalculation() {
    buffering = false;
    String typedEvent = "No event information - buffered dispatch";
    if (guardCheck_alarmPublisher()) {
      alarmPublisher.onChange();
    }
    afterEvent();
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

  private void initialiseAuditor(Auditor auditor) {
    auditor.init();
    auditor.nodeRegistered(callbackDispatcher, "callbackDispatcher");
    auditor.nodeRegistered(subscriptionManager, "subscriptionManager");
    auditor.nodeRegistered(context, "context");
    auditor.nodeRegistered(alarmMonitor, "alarmMonitor");
    auditor.nodeRegistered(alarmPublisher, "alarmPublisher");
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
    eventLogger.processingComplete();
    isDirty_alarmMonitor = false;
  }

  @Override
  public void batchPause() {
    auditEvent(Lifecycle.LifecycleEvent.BatchPause);
    processing = true;

    afterEvent();
    callbackDispatcher.dispatchQueuedCallbacks();
    processing = false;
  }

  @Override
  public void batchEnd() {
    auditEvent(Lifecycle.LifecycleEvent.BatchEnd);
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
      dirtyFlagSupplierMap.put(alarmMonitor, () -> isDirty_alarmMonitor);
    }
    return dirtyFlagSupplierMap.getOrDefault(node, DataFlow.ALWAYS_FALSE);
  }

  @Override
  public void setDirty(Object node, boolean dirtyFlag) {
    if (dirtyFlagUpdateMap.isEmpty()) {
      dirtyFlagUpdateMap.put(alarmMonitor, (b) -> isDirty_alarmMonitor = b);
    }
    dirtyFlagUpdateMap.get(node).accept(dirtyFlag);
  }

  private boolean guardCheck_alarmPublisher() {
    return isDirty_alarmMonitor;
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
      case "alarmMonitor":
        return (T) alarmMonitor;
      case "alarmPublisher":
        return (T) alarmPublisher;
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
    if (node == alarmMonitor) {
      return "alarmMonitor";
    }
    if (node == alarmPublisher) {
      return "alarmPublisher";
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
  public AlarmProcessor newInstance() {
    return new AlarmProcessor();
  }

  @Override
  public AlarmProcessor newInstance(Map<Object, Object> contextMap) {
    return new AlarmProcessor();
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
