package com.telamin.mongoose.replay;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.fluxtion.runtime.output.MessageSink;
import org.agrona.concurrent.BusySpinIdleStrategy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Run in a CHILD JVM by ReplayIndependentReviewTest: a real server in REPLAY mode whose replay fails, in one of two
 * ways, so a failure that reached the default error handler (which exits the process) is seen as the child's exit
 * code, not as the test JVM dying. It prints what it saw and exits 0 when the server survived.
 * <ul>
 *   <li>{@code store}: the replay store throws when the replayer reads the processor's entries (attach).</li>
 *   <li>{@code decoder}: the journal codec throws an AssertionError decoding an indexed entry (delivery).</li>
 * </ul>
 */
public final class ReplayFailureChildMain {

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        InMemoryEventSource<Object> orders = new InMemoryEventSource<>();
        orders.setName("orders");
        InMemoryMessageSink sink = new InMemoryMessageSink();
        ReplayStore store;
        EventJournal journal = new InMemoryEventJournal();
        Map<String, EventCodec> journalled = Map.of();
        if (mode.equals("store")) {
            store = new ReplayStore() {
                @Override
                public void append(String processor, ReplayEntry entry) {
                }

                @Override
                public List<ReplayEntry> entries(String processor) {
                    throw new IllegalStateException("DEMO replay store unavailable");
                }
            };
        } else {
            InMemoryReplayStore memory = new InMemoryReplayStore();
            memory.append("quotes", new ReplayEntry.Indexed("orders", 1, List.of(1_000L)));
            store = memory;
            journal.append("orders", 1, new byte[]{1});
            journalled = Map.of("orders", new EventCodec() {
                @Override
                public byte[] encode(Object item) {
                    return new byte[]{1};
                }

                @Override
                public Object decode(byte[] bytes) {
                    throw new AssertionError("DEMO decoder failure");
                }
            });
        }
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("processor-agent")
                        .put("quotes", new EventProcessorConfig(new ReplayDemoHandler("orders", "controls"))).build())
                .addEventFeed(EventFeedConfig.builder().instance(orders).name("orders").broadcast(true)
                        .agent("orders-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .replay(ReplayConfig.replay(Set.of("quotes"), journalled, journal, store))
                .build();
        MongooseServer server = MongooseServer.bootServer(config, r -> { });
        Thread.sleep(500);
        orders.offer("ord-LIVE");                                       // must not reach a processor whose replay failed
        Thread.sleep(300);
        GroupReplayer replayer = server.replayers().get("processor-agent");
        System.out.println("SURVIVED complete=" + (replayer != null && replayer.complete())
                + " stopped=" + (replayer == null ? null : replayer.stopped("quotes"))
                + " live=" + sink.getMessages());
        server.stop();
        System.exit(0);
    }
}
