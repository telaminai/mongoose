package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ServiceConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * spec-replay-recording acceptance, through a real MongooseServer. A run is recorded in RECORD mode; the same config is
 * booted in REPLAY mode, nothing is published, and the replay driver delivers the recorded stream. The processor must
 * emit exactly what it emitted live. Each test also has a witness: a stream changed in the way the spec forbids does
 * not reproduce the run.
 */
class ReplayRecordingAcceptanceTest {

    static final String ORDERS = "orders", CONTROLS = "controls", PROCESSOR = "quotes";

    /** A booted server and its handles. */
    record Server(MongooseServer server, InMemoryEventSource<Object> orders, InMemoryEventSource<Object> controls,
                  AdminCommandProcessor admin, InMemoryMessageSink sink) implements AutoCloseable {
        List<String> lines() {
            return sink.getMessages().stream().map(String::valueOf).toList();
        }

        void await(int n) throws InterruptedException {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (sink.getMessages().size() < n && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(sink.getMessages().size() >= n, "expected " + n + " lines: " + lines());
        }

        GroupReplayer replayer() {
            return server.replayers().get("processor-agent");
        }

        void awaitReplay(int lines) throws InterruptedException {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while ((replayer() == null || !replayer().complete() || sink.getMessages().size() < lines) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            Thread.sleep(50);
        }

        @Override
        public void close() {
            server.stop();
        }
    }

    static Server boot(ReplayConfig replay) throws Exception {
        InMemoryEventSource<Object> orders = new InMemoryEventSource<>();
        orders.setName(ORDERS);
        orders.setCacheEventLog(true);
        InMemoryEventSource<Object> controls = new InMemoryEventSource<>();
        controls.setName(CONTROLS);
        controls.setCacheEventLog(true);
        InMemoryMessageSink sink = new InMemoryMessageSink();
        AdminCommandProcessor admin = new AdminCommandProcessor();
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("processor-agent")
                        .put(PROCESSOR, new EventProcessorConfig(new ReplayDemoHandler(ORDERS, CONTROLS))).build())
                .addEventFeed(EventFeedConfig.builder().instance(orders).name(ORDERS).broadcast(true)
                        .agent("orders-agent", new BusySpinIdleStrategy()).build())
                .addEventFeed(EventFeedConfig.builder().instance(controls).name(CONTROLS).broadcast(true)
                        .agent("controls-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .addService(new ServiceConfig<>(admin, AdminCommandRegistry.class, "adminService"))
                .replay(replay)
                .build();
        MongooseServer server = MongooseServer.bootServer(config, rec -> { });
        Thread.sleep(200);                              // the processor subscribes in start()
        return new Server(server, orders, controls, admin, sink);
    }

    /** RECORD mode with the orders feed journalled; controls are recorded inline. */
    static ReplayConfig recording(InMemoryReplayStore store, InMemoryEventJournal journal) {
        return ReplayConfig.record(Set.of(PROCESSOR), Map.of(ORDERS, new JavaSerializationCodec()), journal, store);
    }

    static ReplayConfig replaying(ReplayStore store, InMemoryEventJournal journal) {
        return ReplayConfig.replay(Set.of(PROCESSOR), Map.of(ORDERS, new JavaSerializationCodec()), journal, store);
    }

    /** The instant each emitted line carries. */
    static List<Long> times(List<String> lines) {
        return lines.stream().map(l -> Long.parseLong(l.substring(l.lastIndexOf("time=") + 5))).toList();
    }

    /** A store holding {@code entries} for the processor: a recorded stream, changed for a witness. */
    static InMemoryReplayStore storeOf(List<ReplayEntry> entries) {
        InMemoryReplayStore s = new InMemoryReplayStore();
        entries.forEach(e -> s.append(PROCESSOR, e));
        return s;
    }

    @Test
    void R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        List<String> live;
        try (Server s = boot(recording(store, journal))) {
            s.orders().offer("ord-1");     s.await(1);
            s.controls().offer("suspend"); s.await(2);
            s.orders().offer("ord-2");     s.await(4);  // and the breach the graph raises on it
            s.controls().offer("resume");  s.await(5);
            s.orders().offer("ord-3");     s.await(6);
            live = s.lines();
        }
        assertEquals(6, live.size(), live.toString());
        assertTrue(live.get(3).startsWith("breach=Breach[orderId=ord-2, live=2]"), live.toString());
        assertTrue(live.stream().noneMatch(l -> l.contains("JournalledItem")), "the processor received the bare items: " + live);

        // R2/R3: an index for each order (the journalled feed), the controls inline, never the graph's breach
        List<ReplayEntry> entries = store.entries(PROCESSOR);
        assertEquals(5, entries.size(), entries.toString());
        assertInstanceOf(ReplayEntry.Indexed.class, entries.get(0));
        assertInstanceOf(ReplayEntry.Inline.class, entries.get(1));
        assertInstanceOf(ReplayEntry.Indexed.class, entries.get(2));
        assertInstanceOf(ReplayEntry.Inline.class, entries.get(3));
        assertInstanceOf(ReplayEntry.Indexed.class, entries.get(4));
        assertEquals(3, journal.size(ORDERS), "each order journalled once");
        // D3: each entry's instant is the processTime the processor read (the breach is a consequence, not an input)
        List<Long> inputTimes = new ArrayList<>(times(live));
        inputTimes.remove(3);
        assertEquals(inputTimes, entries.stream().map(ReplayEntry::instant).toList());

        // R5: the replay delivers the stream in the processor's order; the graph raises its breach again by itself
        try (Server r = boot(replaying(store, journal))) {
            r.awaitReplay(6);
            assertEquals(live, r.lines(), "the replay does what the run did, at the same instants");
        }

        // witness: each source in its own order, not the processor's, does not reproduce it
        List<ReplayEntry> bySource = new ArrayList<>(entries.stream().filter(e -> e instanceof ReplayEntry.Indexed).toList());
        bySource.addAll(entries.stream().filter(e -> e instanceof ReplayEntry.Inline).toList());
        try (Server w = boot(replaying(storeOf(bySource), journal))) {
            w.awaitReplay(6);
            assertNotEquals(live, w.lines(), "the order across sources is the processor's");
        }
    }

    @Test
    void R4_aTimeoutFiringBetweenInputs_replaysThere() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        List<String> live;
        try (Server s = boot(recording(store, journal))) {
            s.orders().offer("ord-1");  s.await(1);
            s.controls().offer("arm");  s.await(2);
            s.await(3);                                 // the 40 ms timeout fires, on the live scheduler
            s.orders().offer("ord-2");  s.await(4);
            live = s.lines();
        }
        assertTrue(live.get(2).startsWith("timeout armedAt="), live.toString());
        List<ReplayEntry> entries = store.entries(PROCESSOR);
        assertEquals(4, entries.size(), entries.toString());
        assertEquals(new ReplayEntry.TimerFired(1, times(live).get(2)), entries.get(2), "recorded where it fired, at the instant read");

        try (Server r = boot(replaying(store, journal))) {
            r.awaitReplay(4);
            assertEquals(null, r.replayer().stopped(PROCESSOR), "the replay ran to the end");
            assertEquals(live, r.lines(), "the replay fires the timeout at the same point, at the same instant");
        }
        // witness: without the firing, the timeout never happens in the replay
        try (Server w = boot(replaying(storeOf(entries.stream().filter(e -> !(e instanceof ReplayEntry.TimerFired)).toList()), journal))) {
            w.awaitReplay(3);
            assertNotEquals(live, w.lines(), "the firing is an input");
        }
    }

    @Test
    void R6_anAdminCommandBetweenInputs_replaysThere() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        List<String> live;
        List<Object> replies = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (Server s = boot(recording(store, journal))) {
            s.orders().offer("ord-1");  s.await(1);
            AdminCommandRequest request = new AdminCommandRequest();
            request.setCommand("quotes.reset");
            request.setArguments(List.of("DEMO-operator"));
            request.setOutput(replies::add);
            request.setErrOutput(replies::add);
            s.admin().processAdminCommandRequest(request);   // blocks until the processor has run it
            s.await(2);
            s.orders().offer("ord-2");  s.await(3);
            live = s.lines();
        }
        assertEquals(List.of("reset"), replies);
        assertTrue(live.get(2).startsWith("order=ord-2 live=1"), "the reset changed the processor's state: " + live);
        List<ReplayEntry> entries = store.entries(PROCESSOR);
        assertEquals(3, entries.size(), entries.toString());
        ReplayEntry.AdminInvoked admin = assertInstanceOf(ReplayEntry.AdminInvoked.class, entries.get(1));
        assertEquals("quotes.reset", admin.command());
        assertEquals(List.of("DEMO-operator"), admin.args());

        try (Server r = boot(replaying(store, journal))) {
            r.awaitReplay(3);
            assertEquals(live, r.lines(), "the replay runs the command at the same point");
            assertEquals(List.of(PROCESSOR + ": reset"), r.replayer().adminReplies(), "its reply, collected");
        }
        // witness: without the command, the second order is not a reset's first
        try (Server w = boot(replaying(storeOf(entries.stream().filter(e -> !(e instanceof ReplayEntry.AdminInvoked)).toList()), journal))) {
            w.awaitReplay(2);
            assertNotEquals(live.subList(1, 3), w.lines().subList(0, 2));
            assertTrue(w.lines().get(1).startsWith("order=ord-2 live=2"), w.lines().toString());
        }
    }

    @Test
    void D4_aFailedDispatchIsMarked_andTheReplayStopsThere() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        List<String> live;
        try (Server s = boot(recording(store, journal))) {
            s.orders().offer("ord-1");  s.await(1);
            s.orders().offer("boom");
            Thread.sleep(300);
            // a node that throws leaves the processor's `processing` flag set (DefaultEventProcessor and generated
            // processors alike: no try/finally), so every later event is queued as re-entrant and never processed.
            // The retry "succeeds" by queuing. So after a failure the live processor is not just non-deterministic:
            // it has stopped. ord-2 is never handled
            s.orders().offer("ord-2");
            Thread.sleep(300);
            live = s.lines();
        }
        assertEquals(1, live.size(), "the processor stopped at the failure: " + live);
        List<ReplayEntry> entries = store.entries(PROCESSOR);
        assertInstanceOf(ReplayEntry.Failed.class, entries.get(1), entries.toString());
        try (Server r = boot(replaying(store, journal))) {
            r.awaitReplay(1);
            assertEquals(live.subList(0, 1), r.lines(), "up to the failure, and no further");
            String stopped = r.replayer().stopped(PROCESSOR);
            assertNotNull(stopped);
            assertTrue(stopped.contains("DEMO failure on boom"), stopped);
        }
    }

    @Test
    void R1_offChangesNothing() throws Exception {
        try (Server s = boot(null)) {
            s.orders().offer("ord-1");
            s.await(1);
            assertFalse(s.server().replayers().containsKey("processor-agent"));
        }
    }
}
