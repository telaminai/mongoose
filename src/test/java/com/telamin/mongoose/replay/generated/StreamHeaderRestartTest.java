/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.replay.generated;

import com.telamin.fluxtion.runtime.audit.AuditStreamHeader;
import com.telamin.fluxtion.runtime.audit.EventLogControlEvent;
import com.telamin.fluxtion.runtime.audit.StreamHeaderRecord;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance 6 of the evidence-set key, at the server: a static deployment restarted twice writes three runs whose
 * every generation is #1, and the stream headers tell them apart by run id. The processor is a compiled one, generated
 * before the header existed, so its fingerprint reaches the header from its own descriptor.
 */
class StreamHeaderRestartTest {

    private static AuditStreamHeader bootOnce(List<String> runIds) throws Exception {
        List<AuditStreamHeader> headers = new CopyOnWriteArrayList<>();
        MongooseServerConfig config = MongooseServerConfig.builder()
                .serverId("pricing-1")
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("processor-agent")
                        .put("alarms", EventProcessorConfig.builder().handler(new AlarmProcessor())
                                .logLevel(EventLogControlEvent.LogLevel.INFO).build()).build())
                .build();
        MongooseServer server = MongooseServer.bootServer(config, r -> {
            if (r instanceof StreamHeaderRecord h) headers.add(h.header());
        });
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (headers.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
            runIds.add(server.runId());
            assertEquals("pricing-1", server.serverId());
        } finally {
            server.stop();
        }
        assertFalse(headers.isEmpty(), "the configured listener's stream begins with a header");
        for (AuditStreamHeader h : headers) {
            assertEquals(runIds.getLast(), h.host().get("runId"), "no header goes out before the run is named: " + headers);
        }
        return headers.getFirst();
    }

    @Test
    void threeBootsAreThreeRunsEachGenerationOne() throws Exception {
        List<String> runIds = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String fingerprint = new AlarmProcessor().getDescriptor().sourceFingerprint();
        for (int boot = 0; boot < 3; boot++) {
            AuditStreamHeader h = bootOnce(runIds);
            assertEquals(AlarmProcessor.class.getName(), h.processor());
            assertEquals(fingerprint, h.sourceFingerprint());
            assertEquals("pricing-1", h.host().get("serverId"));
            assertEquals(runIds.get(boot), h.host().get("runId"), "the header carries this server's run id");
            assertEquals("alarms", h.host().get("name"));
            assertEquals("1", h.host().get("generation"), "a static deployment restarts at generation 1");
            seen.add(h.host().get("runId"));
        }
        assertEquals(3, seen.size(), "the run id is what separates the restarts");
    }
}
