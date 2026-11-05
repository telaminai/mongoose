package com.telamin.mongoose.extreview.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.fluxtion.runtime.time.ClockStrategy;
import com.telamin.mongoose.control.AgentThreads;
import com.telamin.mongoose.control.ControlRequest;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.replay.GroupRecorder;
import com.telamin.mongoose.replay.GroupReplayer;
import com.telamin.mongoose.replay.InMemoryReplayStore;
import com.telamin.mongoose.replay.ReplayConfig;
import com.telamin.mongoose.replay.ReplayScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** A one-group rig without a server, after ReplayServiceFollowupReviewTest.Rig (public API only). */
final class ExtReviewRig implements AutoCloseable {
    record Rate(int value) { }

    static final class Probe implements DataFlow, Lifecycle {
        final List<String> trace = new ArrayList<>();
        boolean started;
        boolean failStart;
        ClockStrategy clock;
        public void init() { }
        public void addEventFeed(EventFeed feed) { }
        public void setClockStrategy(ClockStrategy strategy) { clock = strategy; }
        public void onEvent(Object event) { }
        public void start() {
            if (failStart) throw new IllegalStateException("start refused by the probe");
            started = true;
            trace.add("start");
        }
        public void stop() { trace.add("stop"); }
        public void tearDown() { }
        public void registerService(Service<?> s) {
            if (s.instance() instanceof Rate r) trace.add("register:" + r.value() + ":" + started);
        }
        public void deRegisterService(Service<?> s) {
            if (s.instance() instanceof Rate r) trace.add("deregister:" + r.value());
        }
    }

    final ConcurrentHashMap<String, Service<?>> services = new ConcurrentHashMap<>();
    final ComposingEventProcessorAgent group;

    ExtReviewRig(InMemoryReplayStore store, boolean replay, Duration timeout) {
        var base = replay ? ReplayConfig.replay(Set.of("p"), Map.of(), null, store)
                : ReplayConfig.record(Set.of("p"), Map.of(), null, store);
        var cfg = base.withDeliveryTimeout(timeout);
        var scheduler = new ReplayScheduler();
        group = new ComposingEventProcessorAgent("g", new EventFlowManager(), null, scheduler, services,
                replay ? null : new GroupRecorder(cfg, new AtomicLong(100)::incrementAndGet),
                replay ? routing -> new GroupReplayer(cfg, routing, scheduler) : null);
        group.setIsolateStartFailuresAtBoot(true);
    }

    ControlRequest add(Probe probe) {
        return group.addNamedEventProcessor(() -> new NamedEventProcessor("p", probe), new ProcessorRegistration("p"));
    }

    void broadcast(Service<?> service, boolean register, long version) throws Exception {
        if (register) services.put(service.serviceName(), service); else services.remove(service.serviceName());
        group.broadcastService(service, register, version);
        group.doWork();
    }

    void work(int cycles) throws Exception {
        for (int i = 0; i < cycles; i++) group.doWork();
    }

    static Service<Rate> rate(int n) { return new Service<>(new Rate(n), Rate.class, "rate"); }

    @Override
    public void close() { try { group.onClose(); } finally { AgentThreads.exit(); } }
}
