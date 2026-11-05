package com.telamin.mongoose.replay;
import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.control.AgentThreads;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
class RecordedStartupFailureTest {
 @ParameterizedTest @ValueSource(strings={"service", "start", "complete", "fatal"})
 void aFailureAfterRecordingAttachesLeavesADurableMarker(String stage) {
  class Flow implements DataFlow, Lifecycle {
   public void init() { }
   public void onEvent(Object event) { }
   public void addEventFeed(EventFeed feed) { }
   public void registerService(Service<?> svc) { if(stage.equals("service")) throw new IllegalStateException("service setup"); }
   public void start() {
    if(stage.equals("start")) throw new IllegalStateException("startup");
    if(stage.equals("fatal")) throw new InternalError("synthetic startup fatal");
   }
   public void startComplete() { if(stage.equals("complete")) throw new IllegalStateException("startComplete"); }
   public void stop() { }
   public void tearDown() { }
  }
  var store = new InMemoryReplayStore();
  var config = ReplayConfig.record(Set.of("p"), Map.of(), null, store);
  var group = new ComposingEventProcessorAgent("g", new EventFlowManager(), null, new ReplayScheduler(),
          new ConcurrentHashMap<>(), new GroupRecorder(config, () -> 1L), null);
  group.setIsolateStartFailuresAtBoot(true);
  group.addNamedEventProcessor(() -> new NamedEventProcessor("p", new Flow()), new ProcessorRegistration("p"));
  try {
   if(stage.equals("fatal")) assertThrows(InternalError.class, group::onStart); else group.onStart();
   var marker = assertInstanceOf(ReplayEntry.Failed.class, store.entries("p").getFirst());
   assertEquals("start", marker.source());
   assertTrue(marker.description().contains("processor start failed"));
  } finally { try { group.onClose(); } finally { AgentThreads.exit(); } }
 }
}
