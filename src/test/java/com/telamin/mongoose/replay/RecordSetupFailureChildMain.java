package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DefaultEventProcessor;
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
import org.agrona.concurrent.BusySpinIdleStrategy;

import java.util.Map;
import java.util.Set;

/**
 * Run in a CHILD JVM by ReplayRound3ReviewTest (review of cd52628, F3): a real server in RECORD mode whose recorded
 * processor accepts its ordinary clock but refuses the recording's. A failure that reached the default error handler
 * exits the process, so it is seen as the child's exit code. A live item is offered. Prints what it saw; exits 0 when the
 * server survived.
 */
public final class RecordSetupFailureChildMain {

    /** A processor whose clock accepts anything but the recording's. */
    public static final class RefusesRecordingClock extends DefaultEventProcessor {
        public RefusesRecordingClock() {
            super(new ReplayRound3ReviewTest.Probe());
        }

        @Override
        public void setClockStrategy(ClockStrategy clockStrategy) {
            if (clockStrategy instanceof RecordingClock) throw new IllegalStateException("DEMO cannot install recording clock");
            super.setClockStrategy(clockStrategy);
        }
    }

    public static void main(String[] args) throws Exception {
        InMemoryEventSource<Object> feed = new InMemoryEventSource<>();
        feed.setName(ReplayRound3ReviewTest.FEED);
        InMemoryMessageSink sink = new InMemoryMessageSink();
        InMemoryReplayStore store = new InMemoryReplayStore();
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName(ReplayRound3ReviewTest.GROUP)
                        .put("probe", EventProcessorConfig.builder().handler(new RefusesRecordingClock()).build()).build())
                .addEventFeed(EventFeedConfig.builder().instance(feed).name(ReplayRound3ReviewTest.FEED).broadcast(true)
                        .agent("feed-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .replay(ReplayConfig.record(Set.of("probe"), Map.of(), null, store))
                .build();
        MongooseServer server = MongooseServer.bootServer(config, r -> { });
        Thread.sleep(500);
        feed.offer("DEMO-LIVE");
        Thread.sleep(300);
        System.out.println("SURVIVED live=" + sink.getMessages() + " entries=" + store.entries("probe"));
        server.stop();
        System.exit(0);
    }
}
