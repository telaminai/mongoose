package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.fluxtion.runtime.time.ClockStrategy;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.mongoose.service.CallBackType;
import org.agrona.concurrent.BusySpinIdleStrategy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Run in a CHILD JVM by ReplayReReviewTest (re-review N1): a real server in REPLAY mode whose replayed processor accepts
 * its ordinary clock but refuses the replay's. A failure that reached the default error handler exits the process, so it
 * is seen as the child's exit code. The processor subscribes by two routes (onEvent and a typed callback), and a live
 * item is offered: neither route may deliver it, and nothing may reach the real sink. Prints what it saw; exits 0 when
 * the server survived.
 */
public final class ReplaySetupFailureChildMain {

    /** A processor whose clock accepts anything but the replay's. */
    public static final class RefusesReplayClock extends ReplayIndependentReviewTest.ProbeProcessor {
        public RefusesReplayClock() {
            super(true, false);
        }

        @Override
        public void setClockStrategy(ClockStrategy clockStrategy) {
            if (clockStrategy instanceof ReplayClock) throw new IllegalStateException("DEMO cannot install replay clock");
            super.setClockStrategy(clockStrategy);
        }
    }

    public static void main(String[] args) throws Exception {
        InMemoryEventSource<Object> feed = new InMemoryEventSource<>();
        feed.setName(ReplayIndependentReviewTest.FEED);
        InMemoryMessageSink sink = new InMemoryMessageSink();
        InMemoryReplayStore store = new InMemoryReplayStore();
        store.append("probe", new ReplayEntry.Inline(ReplayIndependentReviewTest.FEED, "DEMO-recorded", List.of(5L)));
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName(ReplayIndependentReviewTest.GROUP)
                        .put("probe", EventProcessorConfig.builder().handler(new RefusesReplayClock()).build()).build())
                .addEventFeed(EventFeedConfig.builder().instance(feed).name(ReplayIndependentReviewTest.FEED).broadcast(true)
                        .agent("feed-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .eventInvokeStrategy(CallBackType.forClass(ReplayIndependentReviewTest.TypedRoute.class),
                        ReplayIndependentReviewTest.TypedStrategy::new)
                .replay(ReplayConfig.replay(Set.of("probe"), Map.of(), null, store))
                .build();
        MongooseServer server = MongooseServer.bootServer(config, r -> { });
        Thread.sleep(500);
        feed.offer("DEMO-LIVE");                       // must reach neither route of a processor whose replay failed
        Thread.sleep(300);
        GroupReplayer replayer = server.replayers().get(ReplayIndependentReviewTest.GROUP);
        System.out.println("SURVIVED complete=" + (replayer != null && replayer.complete())
                + " stopped=" + (replayer == null ? null : replayer.stopped("probe"))
                + " live=" + sink.getMessages()
                + " outputs=" + (replayer == null ? null : replayer.outputs("probe")));
        server.stop();
        System.exit(0);
    }
}
