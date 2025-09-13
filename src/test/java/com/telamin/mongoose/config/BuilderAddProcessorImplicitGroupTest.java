/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.config;

import com.fluxtion.runtime.node.ObjectEventHandlerNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class BuilderAddProcessorImplicitGroupTest {

    @Test
    public void addProcessor_createsGroupImplicitly_andRegistersHandler() {
        var handler = new ObjectEventHandlerNode() { };
        var cfg = EventProcessorConfig.builder()
                .customHandler(handler)
                .build();

        var app = MongooseServerConfig.builder()
                .addProcessor("proc-agent", "hello-handler", cfg)
                .build();

        List<EventProcessorGroupConfig> groups = app.getEventHandlers();
        Assertions.assertEquals(1, groups.size(), "Exactly one group should be present");
        EventProcessorGroupConfig group = groups.get(0);
        Assertions.assertEquals("proc-agent", group.getAgentName());
        Assertions.assertTrue(group.getEventHandlers().containsKey("hello-handler"));
        Assertions.assertSame(cfg, group.getEventHandlers().get("hello-handler"));
    }

    @Test
    public void addProcessor_twice_reusesSameGroup() {
        var cfg1 = EventProcessorConfig.builder().customHandler(new ObjectEventHandlerNode() { }).build();
        var cfg2 = EventProcessorConfig.builder().customHandler(new ObjectEventHandlerNode() { }).build();

        var builder = MongooseServerConfig.builder();
        builder.addProcessor("proc-agent", "h1", cfg1);
        builder.addProcessor("proc-agent", "h2", cfg2);
        var app = builder.build();

        List<EventProcessorGroupConfig> groups = app.getEventHandlers();
        Assertions.assertEquals(1, groups.size(), "Same agentName should reuse one group");
        var handlers = groups.get(0).getEventHandlers();
        Assertions.assertTrue(handlers.containsKey("h1") && handlers.containsKey("h2"));
    }
}
