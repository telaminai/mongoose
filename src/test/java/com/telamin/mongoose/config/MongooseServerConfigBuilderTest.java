/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.fluxtion.agrona.concurrent.YieldingIdleStrategy;
import com.fluxtion.runtime.EventProcessor;
import com.fluxtion.runtime.audit.EventLogControlEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MongooseServerConfigBuilderTest {

    static class DummyProcessor implements EventProcessor<DummyProcessor> {
        @Override
        public void onEvent(Object o) {
        }

        @Override
        public void init() {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void tearDown() {
        }
    }

    @Test
    void buildComplexAppConfigViaBuilders() {
        // Build an EventProcessorConfig
        EventProcessorConfig<DummyProcessor> epCfg = EventProcessorConfig.<DummyProcessor>builder()
                .handler(new DummyProcessor())
                .logLevel(EventLogControlEvent.LogLevel.INFO)
                .putConfig("key", "value")
                .build();

        // Build a group with the processor
        EventProcessorGroupConfig group = EventProcessorGroupConfig.builder()
                .agentName("groupA")
                .idleStrategy(new BusySpinIdleStrategy())
                .logLevel(EventLogControlEvent.LogLevel.DEBUG)
                .put("proc1", epCfg)
                .build();

        // Build a service via ServiceConfig.Builder
        ServiceConfig<Object> serviceCfg = ServiceConfig.<Object>builder()
                .service(new Object())
                .serviceClass(Object.class)
                .name("svcA")
                .agent("agentGroup1", new YieldingIdleStrategy())
                .build();

        // Build a thread config
        ThreadConfig threadCfg = ThreadConfig.builder()
                .agentName("groupA")
                .idleStrategy(new YieldingIdleStrategy())
                .build();

        // Assemble MongooseServerConfig
        MongooseServerConfig app = MongooseServerConfig.builder()
                .idleStrategy(new YieldingIdleStrategy())
                .addProcessorGroup(group)
                .addService(serviceCfg)
                .addThread(threadCfg)
                .build();

        // Assertions
        assertNotNull(app);
        assertNotNull(app.getEventHandlers());
        assertEquals(1, app.getEventHandlers().size());
        EventProcessorGroupConfig builtGroup = app.getEventHandlers().get(0);
        assertEquals("groupA", builtGroup.getAgentName());
        assertEquals(EventLogControlEvent.LogLevel.DEBUG, builtGroup.getLogLevel());
        assertNotNull(builtGroup.getEventHandlers());
        assertTrue(builtGroup.getEventHandlers().containsKey("proc1"));

        assertNotNull(app.getServices());
        assertEquals(1, app.getServices().size());
        ServiceConfig<?> builtSvc = app.getServices().get(0);
        assertEquals("svcA", builtSvc.getName());
        assertEquals("agentGroup1", builtSvc.getAgentGroup());
        assertNotNull(app.getAgentThreads());
        assertEquals(1, app.getAgentThreads().size());

        // Verify config map content
        EventProcessorConfig<?> epFromGroup = builtGroup.getEventHandlers().get("proc1");
        Object val = epFromGroup.getConfig().get("key");
        assertEquals("value", val);
    }
}
