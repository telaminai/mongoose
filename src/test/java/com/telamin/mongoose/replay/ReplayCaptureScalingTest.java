/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stress-campaign finding: a replayed processor's outputs were captured in a CopyOnWriteArrayList, so each output
 * copied every output before it. Capturing is the replay's hot path (one call per line the processor emits), and a
 * recording of a few hundred thousand outputs took minutes to replay, a million effectively never finished.
 */
@Timeout(120)
class ReplayCaptureScalingTest {

    @Test
    void capturingAReplaysOutputsIsLinear() {
        GroupReplayer replayer = new GroupReplayer(
                ReplayConfig.replay(Set.of("p"), Map.of(), new InMemoryEventJournal(), new InMemoryReplayStore()),
                null, new ReplayScheduler());
        DataFlow flow = new DataFlow() {
            @Override public void onEvent(Object e) { }
        };
        replayer.attach("p", flow);
        @SuppressWarnings("unchecked")
        MessageSink<Object> captured = (MessageSink<Object>) replayer
                .serviceFor(flow, new Service<>(new InMemoryMessageSink(), MessageSink.class, "sink")).instance();
        int n = 300_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) captured.accept("line-" + i);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertEquals(n, replayer.outputs("p").size());
        assertEquals("line-" + (n - 1), replayer.outputs("p").get(n - 1));
        assertTrue(ms < 3_000, "capturing " + n + " outputs took " + ms + " ms");
    }
}
