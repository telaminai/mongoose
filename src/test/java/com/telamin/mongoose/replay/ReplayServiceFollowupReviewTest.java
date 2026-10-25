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
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

class ReplayServiceFollowupReviewTest {
    record Rate(int value) { }
    static final class Probe implements DataFlow, Lifecycle {
        final List<String> trace = new ArrayList<>();
        boolean started;
        boolean failRegistration;
        int rate;
        boolean readClock;
        ClockStrategy clock;
        public void init() { }
        public void addEventFeed(EventFeed feed) { }
        public void setClockStrategy(ClockStrategy strategy) { clock = strategy; }
        public void onEvent(Object event) { }
        public void start() { started = true; trace.add("start:" + rate); }
        public void stop() { }
        public void tearDown() { }
        public void registerService(Service<?> s) {
            if (!(s.instance() instanceof Rate r)) return;
            if (failRegistration) throw new IllegalStateException("registration failed during replay");
            rate = r.value();
            trace.add("register:" + rate + ":" + started);
            if (readClock) trace.add("time:" + clock.getWallClockTime());
        }
        public void deRegisterService(Service<?> s) {
            if (s.instance() instanceof Rate r) { trace.add("deregister:" + r.value()); rate = 0; }
        }
    }
    static final class Rig implements AutoCloseable {
        final ConcurrentHashMap<String, Service<?>> services = new ConcurrentHashMap<>();
        final ComposingEventProcessorAgent group;
        Rig(InMemoryReplayStore store, boolean replay, Service<?> initial) {
            if (initial != null) services.put(initial.serviceName(), initial);
            var cfg = replay ? ReplayConfig.replay(Set.of("p"), Map.of(), null, store)
                    : ReplayConfig.record(Set.of("p"), Map.of(), null, store);
            var scheduler = new ReplayScheduler();
            group = new ComposingEventProcessorAgent("g", new EventFlowManager(), null, scheduler, services,
                    replay ? null : new GroupRecorder(cfg, new java.util.concurrent.atomic.AtomicLong(100)::incrementAndGet),
                    replay ? routing -> new GroupReplayer(cfg, routing, scheduler) : null);
        }
        void start(Probe probe) {
            var add = group.addNamedEventProcessor(() -> new NamedEventProcessor("p", probe), new ProcessorRegistration("p"));
            group.onStart();
            assertTrue(add.reply().getNow(null).applied());
        }
        void broadcast(Service<?> service, boolean register, long version) throws Exception {
            if (register) services.put(service.serviceName(), service); else services.remove(service.serviceName());
            group.broadcastService(service, register, version);
            group.doWork();
        }
        void finishReplay() throws Exception {
            for (int i = 0; i < 10; i++) group.doWork();
            assertTrue(group.replayer().complete());
        }
        public void close() { try { group.onClose(); } finally { AgentThreads.exit(); } }
    }
    static Service<Rate> rate(int n) { return new Service<>(new Rate(n), Rate.class, "rate"); }

    @Test void aBroadcastAlreadyDeliveredAtBuildMustNotMoveTheServicePastStartOnReplay() throws Exception {
        var store = new InMemoryReplayStore();
        var service = rate(1);
        var live = new Probe();
        try (var rig = new Rig(store, false, service)) {
            rig.start(live);
            // Legal admission interleaving: registry publication precedes Add's build; broadcast is consumed later.
            rig.broadcast(service, true, 1);
        }
        var replayed = new Probe();
        try (var rig = new Rig(store, true, service)) {
            rig.start(replayed);
            rig.finishReplay();
            assertNull(rig.group.replayer().stopped("p"), "replay reports no divergence");
            assertEquals(live.trace, replayed.trace, "initial injection and start must keep their recorded order");
        }
    }

    @Test void serviceVersionsMustNotReplayUsingTheLatestIncarnationForEveryRegistration() throws Exception {
        var store = new InMemoryReplayStore();
        var oldService = rate(1);
        var replacement = rate(2);
        var live = new Probe();
        try (var rig = new Rig(store, false, null)) {
            rig.start(live);
            rig.broadcast(oldService, true, 1);
            rig.broadcast(oldService, false, 2);
            rig.broadcast(replacement, true, 3);
        }
        var replayed = new Probe();
        try (var rig = new Rig(store, true, replacement)) {
            rig.start(replayed);
            rig.broadcast(replacement, true, 3); // explicitly tell the group which registry version is available
            rig.finishReplay();
            assertNotNull(rig.group.replayer().stopped("p"), "an unavailable historical incarnation must stop replay");
            assertTrue(rig.group.replayer().stopped("p").contains("version 1 is unavailable"));
            assertEquals(List.of("start:0"), replayed.trace, "replacement must never substitute for the old object");
        }
    }

    @Test void aPreviouslyPublishedButWithdrawnIncarnationCannotBeResurrectedFromHistory() throws Exception {
        var store = new InMemoryReplayStore();
        store.append("p", new ReplayEntry.ServiceChanged("rate", true, 1, 100, List.of()));
        var oldService = rate(1);
        var replacement = rate(2);
        var replayed = new Probe();
        try (var rig = new Rig(store, true, null)) {
            rig.start(replayed);
            // All three publications precede the next agent turn. A historical object can still be referenced by
            // queued broadcasts, but the registry has withdrawn it (and may already have stopped its lifecycle).
            rig.services.put("rate", oldService);
            rig.group.broadcastService(oldService, true, 1);
            rig.services.remove("rate");
            rig.group.broadcastService(oldService, false, 2);
            rig.services.put("rate", replacement);
            rig.group.broadcastService(replacement, true, 3);
            rig.finishReplay();
            assertNotNull(rig.group.replayer().stopped("p"));
            assertTrue(rig.group.replayer().stopped("p").contains("version 1 is unavailable"));
            assertEquals(List.of("start:0"), replayed.trace, "a withdrawn historical binding is not a live service");
        }
    }

    @Test void aThrowingReplayedServiceHandlerMustStopReplayInsteadOfReportingSuccess() throws Exception {
        var store = new InMemoryReplayStore();
        var service = rate(1);
        try (var rig = new Rig(store, false, null)) {
            rig.start(new Probe());
            rig.broadcast(service, true, 1);
        }
        var replayed = new Probe();
        replayed.failRegistration = true;
        try (var rig = new Rig(store, true, service)) {
            rig.start(replayed);
            rig.broadcast(service, true, 1);
            rig.finishReplay();
            assertNotNull(rig.group.replayer().stopped("p"), "a failed callback must not count as a successful replay");
            assertTrue(rig.group.replayer().stopped("p").contains("registration failed during replay"));
        }
    }
    @Test void exactServiceIncarnationsReplayInOrderWithTheirRecordedClocks() throws Exception {
        var store = new InMemoryReplayStore();
        var oldService = rate(1);
        var replacement = rate(2);
        var live = new Probe();
        live.readClock = true;
        try (var rig = new Rig(store, false, null)) {
            rig.start(live);
            rig.broadcast(oldService, true, 1);
            rig.broadcast(oldService, false, 2);
            rig.broadcast(replacement, true, 3);
        }
        var replayed = new Probe();
        replayed.readClock = true;
        try (var rig = new Rig(store, true, null)) {
            rig.start(replayed);
            rig.broadcast(oldService, true, 1);
            rig.broadcast(oldService, false, 2);
            rig.broadcast(replacement, true, 3);
            rig.finishReplay();
            assertNull(rig.group.replayer().stopped("p"));
            assertEquals(live.trace, replayed.trace);
        }
    }

    @Test void aFailedLiveServiceCallbackIsRecordedAsFailureRatherThanSuccessfulChange() throws Exception {
        var store = new InMemoryReplayStore();
        var service = rate(1);
        var live = new Probe();
        live.failRegistration = true;
        try (var rig = new Rig(store, false, null)) {
            rig.start(live);
            rig.broadcast(service, true, 1);
        }
        assertInstanceOf(ReplayEntry.Failed.class, store.entries("p").getFirst());
        assertTrue(store.entries("p").stream().noneMatch(e -> e instanceof ReplayEntry.ServiceChanged));
        try (var rig = new Rig(store, true, null)) {
            rig.start(new Probe());
            rig.finishReplay();
            assertNotNull(rig.group.replayer().stopped("p"));
        }
    }

    @Test void anUnknownPositiveServiceVersionCannotSilentlyUseAConfiguredObject() throws Exception {
        var store = new InMemoryReplayStore();
        store.append("p", new ReplayEntry.ServiceChanged("rate", true, 7, 100, List.of()));
        var replayed = new Probe();
        try (var rig = new Rig(store, true, rate(7))) {
            rig.start(replayed);
            rig.finishReplay();
            assertNotNull(rig.group.replayer().stopped("p"));
            assertTrue(rig.group.replayer().stopped("p").contains("no registry binding identifies recorded service"));
            assertEquals(List.of("start:0"), replayed.trace);
        }
    }

}
