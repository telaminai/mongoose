package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.time.ClockStrategy;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.fluxtion.runtime.service.Service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ReplayStatusPublicationTest {
    @Test void aCursorBeingLoadedIsVisibleButNotReportedComplete() throws Exception {
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        DataFlow flow = new DataFlow() {
            public void onEvent(Object e) { }
            public void setClockStrategy(ClockStrategy c) {
                entered.countDown();
                try { assertTrue(proceed.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }
        };
        var store = new InMemoryReplayStore();
        store.append("p", new ReplayEntry.Failed("test", "recorded failure", 1));
        var replay = new GroupReplayer(ReplayConfig.replay(Set.of("p"), Map.of(), null, store), null,
                new ReplayScheduler());
        var failure = new AtomicReference<Throwable>();
        Thread agent = new Thread(() -> {
            try { replay.attach("p", flow); } catch (Throwable t) { failure.set(t); }
        });
        agent.start();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(replay.replays(flow));
            assertFalse(replay.complete(), "empty entries during setup are not a completed stream");
            assertNull(replay.stopped("p"));
            assertFalse(replay.suppressesLiveServices(flow));
            assertFalse(replay.suppressesLiveService(flow, "svc"));
            assertFalse(replay.defersService(flow, "svc"));
            assertEquals(List.of(), replay.outputs("p"));
            assertEquals(List.of(), replay.controlRequests("p"));
            assertEquals(List.of(), replay.adminReplies());
        } finally { proceed.countDown(); agent.join(3000); }
        assertNull(failure.get());
        assertFalse(replay.complete());
        replay.doWork();
        assertTrue(replay.complete());
        assertTrue(replay.stopped("p").contains("recorded failure"));
        replay.detach(flow);
        assertTrue(replay.stopped("p").contains("recorded failure"));
    }

    @Test void outputSnapshotsRemainStableWhileTheAgentAppends() throws Exception {
        DataFlow flow = new DataFlow() { public void onEvent(Object e) { } };
        var replay = new GroupReplayer(ReplayConfig.replay(Set.of("p"), Map.of(), null,
                new InMemoryReplayStore()), null, new ReplayScheduler());
        replay.attach("p", flow);
        MessageSink<Object> live = new MessageSink<>() {
            public void accept(Object value) { fail("must not deliver live"); }
            public void setValueMapper(java.util.function.Function<? super Object, ?> mapper) { }
        };
        @SuppressWarnings({"unchecked", "rawtypes"})
        Service<?> service = new Service(live, MessageSink.class, "sink");
        Service<?> captured = replay.serviceFor(flow, service);
        @SuppressWarnings("unchecked") MessageSink<Object> sink = (MessageSink<Object>) captured.instance();
        var failure = new AtomicReference<Throwable>();
        Thread writer = new Thread(() -> {
            try { for (int i = 0; i < 2000; i++) sink.accept(i); }
            catch (Throwable t) { failure.set(t); }
        });
        writer.start();
        List<Object> prior = replay.outputs("p");
        int size = prior.size();
        while (writer.isAlive()) {
            List<Object> snapshot = replay.outputs("p");
            for (int i = 0; i < snapshot.size(); i++) assertEquals(i, snapshot.get(i));
        }
        writer.join();
        assertNull(failure.get());
        assertEquals(size, prior.size());
        assertEquals(2000, replay.outputs("p").size());
        assertThrows(UnsupportedOperationException.class, () -> prior.add(1));
        replay.detach(flow);
        assertTrue(replay.complete());
        assertNull(replay.stopped("p"), "a fully consumed stream may detach successfully");
    }
}
