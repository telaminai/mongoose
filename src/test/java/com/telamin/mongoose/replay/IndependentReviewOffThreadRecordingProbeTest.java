package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.fluxtion.runtime.time.ClockStrategy;
import com.telamin.mongoose.control.AgentThreads;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent review of 9b402a3 finding 11 (control requests made off the processor's thread are recorded Failed).
 * The classification asks AgentThreads.isAgentThread(), which is true on ANY processor group's agent thread, not only
 * on the thread of the group that runs the recorded processor.
 */
class IndependentReviewOffThreadRecordingProbeTest {

    static final class Probe implements DataFlow, Lifecycle {
        final AtomicReference<MongooseServerController> controller = new AtomicReference<>();
        public void init() { }
        public void addEventFeed(EventFeed feed) { }
        public void setClockStrategy(ClockStrategy strategy) { }
        public void onEvent(Object event) { }
        public void start() { }
        public void stop() { }
        public void tearDown() { }
        public void registerService(Service<?> s) {
            if (s.instance() instanceof MongooseServerController c) controller.set(c);
        }
        public void deRegisterService(Service<?> s) { }
    }

    /**
     * A recorded processor's controller wrapper, invoked from another group's agent thread (a callback it handed to a
     * processor in another group): outside this processor's input order, so the stream cannot reproduce it, exactly as
     * for an ordinary thread. It is recorded as an in-order ControlCall instead, and a replay would expect it in order.
     */
    @Test
    void aControlRequestFromAnotherGroupsAgentThreadMustBeRecordedAsNotReproducible() throws Exception {
        var store = new InMemoryReplayStore();
        var services = new ConcurrentHashMap<String, Service<?>>();
        MongooseServerController target = (MongooseServerController) Proxy.newProxyInstance(
                MongooseServerController.class.getClassLoader(), new Class<?>[]{MongooseServerController.class},
                (p, m, a) -> m.getReturnType() == boolean.class ? false : null);
        services.put("controller", new Service<>(target, MongooseServerController.class, "controller"));
        var cfg = ReplayConfig.record(Set.of("p"), Map.of(), null, store);
        var scheduler = new ReplayScheduler();
        var group = new ComposingEventProcessorAgent("g", new EventFlowManager(), null, scheduler, services,
                new GroupRecorder(cfg, () -> 1L), null);
        var probe = new Probe();
        try {
            var add = group.addNamedEventProcessor(() -> new NamedEventProcessor("p", probe), new ProcessorRegistration("p"));
            group.onStart();
            assertTrue(add.reply().getNow(null).applied());
            MongooseServerController wrapped = probe.controller.get();
            assertNotNull(wrapped, "the processor was handed the controller");
            assertTrue(Proxy.isProxyClass(wrapped.getClass()), "and it is the recording wrapper");

            // the same request, from a plain thread and from another group's agent thread
            Thread plain = new Thread(() -> wrapped.stopService("x"));
            plain.start();
            plain.join();
            Thread otherGroup = new Thread(() -> {
                AgentThreads.enter(new Object());         // another group's thread, marked as that group marks it
                try {
                    wrapped.stopService("x");
                } finally {
                    AgentThreads.exit();
                }
            });
            otherGroup.start();
            otherGroup.join();

            List<ReplayEntry> entries = store.entries("p");
            assertEquals(2, entries.size(), entries.toString());
            assertInstanceOf(ReplayEntry.Failed.class, entries.get(0), "from a plain thread: not reproducible");
            assertInstanceOf(ReplayEntry.Failed.class, entries.get(1),
                    "from another group's agent thread it is equally outside this processor's input order, but was recorded"
                            + " as an in-order control call: " + entries.get(1));
        } finally {
            group.onClose();
            AgentThreads.exit();
        }
    }
}
