/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.example.replay;

import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.*;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots a Fluxtion server with an in-memory event source and demonstrates replay by
 * publishing ReplayRecord instances. The handler records getContext().getClock()
 * values to show deterministic, data-driven time during replay.
 */
public class ReplayServerBootExample {

    @Test
    public void boot_server_and_replay_with_data_driven_clock() throws Exception {
        // Create an event source service (in-VM)
        InMemoryEventSource<ReplayRecord> source = new InMemoryEventSource<>();
        source.setName("replayFeed");
        source.setCacheEventLog(true);

        // Processor subscribes to the feed name and writes event+time to the sink
        ReplayCaptureHandler handler = new ReplayCaptureHandler(source.getName());

        // In-memory sink to capture outputs
        InMemoryMessageSink sink = new InMemoryMessageSink();

        // Build server configuration
        EventProcessorGroupConfig processors = EventProcessorGroupConfig.builder()
                .agentName("processor-agent")
                .put("replay-listener", new EventProcessorConfig(handler))
                .build();

        EventFeedConfig<?> feedCfg = EventFeedConfig.builder()
                .instance(source)
                .name(source.getName())
                .broadcast(true)
                .agent("replay-agent", new BusySpinIdleStrategy())
                .build();

        EventSinkConfig<MessageSink<?>> sinkCfg = EventSinkConfig.<MessageSink<?>>builder()
                .instance(sink)
                .name("memSink")
                .build();

        MongooseServerConfig mongooseServerConfig = MongooseServerConfig.builder()
                .addProcessorGroup(processors)
                .addEventFeed(feedCfg)
                .addEventSink(sinkCfg)
                .build();

        LogRecordListener logs = rec -> {};
        MongooseServer server = MongooseServer.bootServer(mongooseServerConfig, logs);
        try {
            // Publish two replay records with explicit timestamps (data-driven clock)
            long t1 = 1_696_000_000_000L; // example epoch millis
            long t2 = t1 + 1234;
            ReplayRecord r1 = new ReplayRecord();
            r1.setEvent("alpha");
            r1.setWallClockTime(t1);
            ReplayRecord r2 = new ReplayRecord();
            r2.setEvent("beta");
            r2.setWallClockTime(t2);
            source.offer(r1);
            source.offer(r2);

            // Wait for the handler to emit two outputs
            List<Object> out = waitForMessages(sink, 2, 5, TimeUnit.SECONDS);

            // Parse times from the sink messages and assert they match the replay timestamps
            long parsedT1 = extractTime(out.get(0).toString());
            long parsedT2 = extractTime(out.get(1).toString());
            assertEquals(t1, parsedT1);
            assertEquals(t2, parsedT2);

            assertTrue(out.get(0).toString().contains("alpha"));
            assertTrue(out.get(1).toString().contains("beta"));
        } finally {
            server.stop();
        }
    }

    private static long extractTime(String line) {
        Pattern p = Pattern.compile("time=([0-9]+)");
        Matcher m = p.matcher(line);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        throw new IllegalStateException("No time=... found in: " + line);
    }

    private static List<Object> waitForMessages(InMemoryMessageSink sink, int minCount, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        List<Object> snap;
        do {
            snap = sink.getMessages();
            if (snap.size() >= minCount) {
                return snap;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return snap;
    }
}
