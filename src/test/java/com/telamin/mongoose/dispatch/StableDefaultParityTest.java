/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.internal.*;
import com.telamin.mongoose.service.*;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

/** The external reviewer's portable parity probe, which passes on stable v1.0.33 and here (D2 parity). */
class StableDefaultParityTest {
    @Test void unconfiguredQueueRetainsCumulativeHealthAndReasonAfterResubscription() throws Exception {
        var counters = new AgronaCountersService();
        var health = new DefaultMongooseHealth(counters);
        var manager = new EventFlowManager();
        manager.setCountersService(counters); manager.setHealthService(health);
        var publisher = manager.registerEventSource("feed", new InMemoryEventSource<Integer>());
        var group = new ComposingEventProcessorAgent("risk", manager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        var key = new EventSourceKey<Integer>("feed");
        var reader = manager.getMappingAgent(key, CallBackType.ON_EVENT_CALL_BACK, group);
        DataFlow flow = e -> {};
        reader.registerProcessor(flow);
        for(int i=0; i<1025; i++) publisher.publish(i);
        var first = health.aggregatedVerdict("feed");
        assertEquals("DEGRADED", first.verdict().name());
        assertTrue(first.reason().contains("1 item(s) dropped by queue"));
        assertEquals(1, publisher.droppedCount());
        var records = group.control().audit().records();
        assertEquals(1, records.stream().filter(r -> r.contains("record: QueueDrop")).count(), records.toString());
        assertTrue(records.stream().anyMatch(r -> r.contains("queue full after 10 ms spin")));
        reader.deregisterProcessor(flow); reader.onClose();
        var secondReader = manager.getMappingAgent(key, CallBackType.ON_EVENT_CALL_BACK, group);
        secondReader.registerProcessor(flow);
        Thread.sleep(1100);
        var second = health.aggregatedVerdict("feed");
        assertEquals(first.verdict(), second.verdict());
        assertEquals(first.reason(), second.reason());
        assertTrue(group.control().audit().records().stream().noneMatch(r -> r.contains("record: OverloadState")), "no new group overload audit on an unconfigured feed");
        System.out.println("PARITY verdict="+second.verdict()+" reason="+second.reason().replaceAll("#[0-9a-f]+", "#IDENTITY")
                +" cumulativeDrops="+publisher.droppedCount()+" groupRecords="+group.control().audit().records().stream().filter(r -> r.contains("record: QueueDrop")).count());
        secondReader.deregisterProcessor(flow); secondReader.onClose(); manager.stop();
    }
}

