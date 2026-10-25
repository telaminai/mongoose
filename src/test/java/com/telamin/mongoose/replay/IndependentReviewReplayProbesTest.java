package com.telamin.mongoose.replay;

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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent review of the PR 76 fix commits (9b402a3, 1ba5cd9, e57ce2f, 0dea3b3): probes at the edges of the
 * replay service-delivery fixes (follow-up findings 3, 4, 5). Each test asserts the behaviour the owner's rules
 * require; a failure at 0dea3b3 demonstrates the defect it names.
 */
class IndependentReviewReplayProbesTest {

    record Rate(int value) { }

    static final class Probe implements DataFlow, Lifecycle {
        final List<String> trace = new ArrayList<>();
        boolean started;
        int rate;
        public void init() { }
        public void addEventFeed(EventFeed feed) { }
        public void setClockStrategy(ClockStrategy strategy) { }
        public void onEvent(Object event) { }
        public void start() { started = true; trace.add("start:" + rate); }
        public void stop() { }
        public void tearDown() { }
        public void registerService(Service<?> s) {
            if (!(s.instance() instanceof Rate r)) return;
            rate = r.value();
            trace.add("register:" + rate + ":" + started);
        }
        public void deRegisterService(Service<?> s) {
            if (s.instance() instanceof Rate r) { trace.add("deregister:" + r.value()); rate = 0; }
        }
    }

    static final class Rig implements AutoCloseable {
        final ConcurrentHashMap<String, Service<?>> services = new ConcurrentHashMap<>();
        final ComposingEventProcessorAgent group;
        Rig(InMemoryReplayStore store, boolean replay, Service<?> initial) {
            this(store, replay, initial, java.time.Duration.ofSeconds(5));
        }
        Rig(InMemoryReplayStore store, boolean replay, Service<?> initial, java.time.Duration timeout) {
            if (initial != null) services.put(initial.serviceName(), initial);
            var base = replay ? ReplayConfig.replay(Set.of("p"), Map.of(), null, store)
                    : ReplayConfig.record(Set.of("p"), Map.of(), null, store);
            var cfg = base.withDeliveryTimeout(timeout);
            var scheduler = new ReplayScheduler();
            group = new ComposingEventProcessorAgent("g", new EventFlowManager(), null, scheduler, services,
                    replay ? null : new GroupRecorder(cfg, () -> 1L),
                    replay ? routing -> new GroupReplayer(cfg, routing, scheduler) : null);
        }
        void start(Probe probe) {
            var add = group.addNamedEventProcessor(() -> new NamedEventProcessor("p", probe), new ProcessorRegistration("p"));
            group.onStart();
            assertTrue(add.reply().getNow(null).applied());
        }
        ControlRequest broadcast(Service<?> service, boolean register, long version) throws Exception {
            if (register) services.put(service.serviceName(), service); else services.remove(service.serviceName());
            ControlRequest request = group.broadcastService(service, register, version);
            group.doWork();
            return request;
        }
        void finishReplay() throws Exception {
            for (int i = 0; i < 10; i++) group.doWork();
            assertTrue(group.replayer().complete());
        }
        public void close() { try { group.onClose(); } finally { AgentThreads.exit(); } }
    }

    static Service<Rate> rate(int n) { return new Service<>(new Rate(n), Rate.class, "rate"); }

    /**
     * Finding 3's fallback: the recorded run was given the service at runtime (version 1); the replay run has the
     * service configured at boot, so its group holds no version history for it. The replay then hands the processor
     * whatever the registry holds, which here carries different data, and reports neither divergence nor "unverified":
     * only a log line says so. The owner's rule: a replay never silently substitutes different service data.
     */
    @Test
    void theUnversionedRegistryFallbackMustNotSubstituteDifferentServiceDataSilently() throws Exception {
        var store = new InMemoryReplayStore();
        var live = new Probe();
        try (var rig = new Rig(store, false, null)) {
            rig.start(live);
            rig.broadcast(rate(1), true, 1);
        }
        assertEquals(List.of("start:0", "register:1:true"), live.trace);

        var replayed = new Probe();
        // No publication will arrive: expire the permitted publication window without a wall-clock sleep.
        try (var rig = new Rig(store, true, rate(2), java.time.Duration.ZERO)) { // different instance, no history
            rig.start(replayed);
            rig.finishReplay();
            String stopped = rig.group.replayer().stopped("p");
            assertTrue(stopped != null || live.trace.equals(replayed.trace),
                    "the replay substituted the boot-configured instance for the recorded one and reported success:"
                            + " recorded=" + live.trace + ", replayed=" + replayed.trace + ", stopped=" + stopped);
        }
    }

    /**
     * Finding 4/5's edge: the recorded processor held the service from its build (configuration, never recorded) and
     * the stream records only its runtime withdrawal. In the replay run the service reaches the group after the
     * processor was built; that live delivery is suppressed because the stream names the service, so the processor
     * never holds it. The replayed withdrawal then finds nothing to withdraw and is reported as reproduced, although
     * the recorded processor ran its deregistration callback and the replayed one ran neither callback.
     */
    @Test
    void aRecordedWithdrawalOfAServiceTheReplayedProcessorNeverHeldMustBeReportedAsDivergence() throws Exception {
        var store = new InMemoryReplayStore();
        var service = rate(1);
        var live = new Probe();
        try (var rig = new Rig(store, false, service)) {      // held from build in the recorded run
            rig.start(live);
            rig.broadcast(service, false, 1);                 // withdrawn at runtime: recorded
        }
        assertEquals(List.of("register:1:false", "start:1", "deregister:1"), live.trace);
        assertEquals(1, store.entries("p").size(), "only the withdrawal is in the stream: " + store.entries("p"));

        var replayed = new Probe();
        try (var rig = new Rig(store, true, null)) {          // the service arrives after the build in this run
            rig.start(replayed);
            ControlRequest arrival = rig.broadcast(service, true, 0);
            assertTrue(arrival.reply().join().applied(), "the live arrival is answered applied, but suppressed");
            rig.finishReplay();
            String stopped = rig.group.replayer().stopped("p");
            assertTrue(stopped != null || live.trace.equals(replayed.trace),
                    "a withdrawal of a service the processor never held was reported as reproduced:"
                            + " recorded=" + live.trace + ", replayed=" + replayed.trace + ", stopped=" + stopped);
        }
    }

    /**
     * The suppression's other face: a live broadcast suppressed for a replayed processor is answered SUCCEEDED, so
     * the broadcaster's request is APPLIED while the processor still holds the service (the stream withdraws it
     * later, in position). MongooseServer.removeService takes an APPLIED reply from every group as confirmation of
     * withdrawal and stops the retired instance at once (retirement.stop()), so in a replay the service instance is
     * stopped while the replayed processor still holds and may call it; in the recorded run it was stopped only after
     * the processor's deregistration callback. Ownership of the retired instance is not preserved through the replay.
     */
    @Test
    void aSuppressedReplayDeliveryMustNotBeAnsweredAppliedWhileTheProcessorStillHoldsTheService() throws Exception {
        var store = new InMemoryReplayStore();
        var service = rate(1);
        try (var rig = new Rig(store, false, null)) {
            rig.start(new Probe());
            rig.broadcast(service, true, 1);
            rig.broadcast(service, false, 2);
        }

        var replayed = new Probe();
        try (var rig = new Rig(store, true, null)) {
            rig.start(replayed);
            rig.services.put("rate", service);
            rig.group.broadcastService(service, true, 1);
            rig.group.doWork();                                   // suppressed live register; stream delivers v1
            assertEquals(1, replayed.rate, "the stream delivered the recorded registration");

            rig.services.remove("rate");
            ControlRequest withdrawal = rig.group.broadcastService(service, false, 2);
            rig.group.control().drain(64, Long.MAX_VALUE);         // the control mailbox only: not the replay stream
            assertTrue(withdrawal.reply().isDone());
            boolean confirmed = withdrawal.reply().join().applied();
            assertFalse(confirmed && replayed.rate == 1,
                    "the withdrawal was confirmed APPLIED to its sender while the processor still holds the service"
                            + " (rate=" + replayed.rate + "): removeService would stop the instance now");
            rig.finishReplay();
        }
    }
}
