/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.pool;

import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.example.objectpool.PoolEventSourceServerExample;
import com.telamin.mongoose.service.*;
import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.ObjectPoolsRegistry;
import com.telamin.mongoose.service.pool.impl.Pools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Boots a MongooseServer and validates that pooled objects are returned to the pool
 * once processing is complete across the full publish -> queue -> processor pipeline.
 */
public class ObjectPoolServerIntegrationTest {

    private MongooseServer server;

    @AfterEach
    void cleanup() {
        if (server != null) {
            try {
                server.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    @Test
    public void testServerBootAndPoolReturnNowrap() throws Exception {
        Pools.SHARED.remove(PooledMessage.class);
        ObjectPool<PooledMessage> pool = Pools.SHARED.getOrCreate(PooledMessage.class, PooledMessage::new, pm -> pm.value = null);

        // Build config with mapping for ON_EVENT
        MongooseServerConfig cfg = MongooseServerConfig.builder()
                .idleStrategy(new BusySpinIdleStrategy())
                .onEventInvokeStrategy(EventToOnEventInvokeStrategy::new)
                .build();

        server = new MongooseServer(cfg);

        // Register event source and processor
        TestPooledEventSource source = new TestPooledEventSource();
        server.registerEventSource("poolSource", source);
        server.addEventProcessor("proc", "groupA", new BusySpinIdleStrategy(), SubscribingProcessor::new);

        server.init();
        server.start();

        // Acquire message and publish
        PooledMessage msg = pool.acquire();
        msg.value = "serverNowrap";
        source.publish(msg);

        // await up to ~500ms for async processing to complete and return to pool
        long deadline = System.currentTimeMillis() + 500;
        while (pool.availableCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, pool.availableCount(), "message should be returned to pool after processing");
    }

    @Test
    public void testServerBootAndPoolReturnNamedEvent() throws Exception {
        Pools.SHARED.remove(PooledMessage.class);
        ObjectPool<PooledMessage> pool = Pools.SHARED.getOrCreate(PooledMessage.class, PooledMessage::new, pm -> pm.value = null);

        // Build config with mapping for ON_EVENT
        MongooseServerConfig cfg = MongooseServerConfig.builder()
                .idleStrategy(new BusySpinIdleStrategy())
                .onEventInvokeStrategy(EventToOnEventInvokeStrategy::new)
                .build();

        server = new MongooseServer(cfg);

        // Register event source configured to wrap with named event
        TestPooledEventSource source = new TestPooledEventSource();
        source.setEventWrapStrategy(EventSource.EventWrapStrategy.SUBSCRIPTION_NAMED_EVENT);
        server.registerEventSource("poolSource", source);
        server.addEventProcessor("proc", "groupB", new BusySpinIdleStrategy(), SubscribingProcessor::new);

        server.init();
        server.start();

        // Acquire message and publish
        PooledMessage msg = pool.acquire();
        msg.value = "serverNamed";
        source.publish(msg);

        // await up to ~500ms for async processing to complete and return to pool
        long deadline = System.currentTimeMillis() + 500;
        while (pool.availableCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, pool.availableCount(), "message should be returned to pool after processing (named event)");
    }

    @Test
    public void testServerBootAndReturnMappedEvent() throws Exception {
        Pools.SHARED.remove(PooledMessage.class);
        Pools.SHARED.remove(MappedPoolMessage.class);
        ObjectPool<PooledMessage> pool = Pools.SHARED.getOrCreate(PooledMessage.class, PooledMessage::new, pm -> pm.value = null);
        ObjectPool<MappedPoolMessage> pool2 = Pools.SHARED.getOrCreate(MappedPoolMessage.class, MappedPoolMessage::new, MappedPoolMessage::reset);

        MongooseServerConfig cfg = MongooseServerConfig.builder()
                .idleStrategy(new BusySpinIdleStrategy())
                .onEventInvokeStrategy(EventToOnEventInvokeStrategy::new)
                .build();

        server = new MongooseServer(cfg);
        server.registerService(new Service<>(Pools.SHARED, ObjectPoolsRegistry.class, ObjectPoolsRegistry.SERVICE_NAME));

        TestPooledEventSource source = new TestPooledEventSource();
        PoolingDataMapper dataMapper = new PoolingDataMapper();
        source.setDataMapper(dataMapper);
        server.registerEventSource("poolSource", source, dataMapper);
        server.addEventProcessor("proc", "groupC", new BusySpinIdleStrategy(), SubscribingProcessor::new);
        server.init();
        server.start();

        PooledMessage msg = pool.acquire();
        msg.value = "twr-nowrap";
        source.publish(msg);

        long deadline = System.currentTimeMillis() + 500;
        while (pool.availableCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        // input message should be returned to pool
        assertEquals(1, pool.availableCount(), "message should be returned using try-with-resources (nowrap)");

        while (dataMapper.getPool().availableCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        // output message should be returned to pool
        assertEquals(1, dataMapper.getPool().availableCount(), "message should be returned using try-with-resources (nowrap)");
    }


    @Test
    public void testServerNamedEvent_tryWithResources() throws Exception {
        PooledEventSource source = new PooledEventSource();

        MongooseServerConfig cfg = new MongooseServerConfig()
                .addProcessor("thread-p1", new PoolEventSourceServerExample.MyHandler(), "processor")
                .addEventSource(source, "pooledSource", true);
        server = MongooseServer.bootServer(cfg, (l) -> {});


        ObjectPool<PooledMessage> pool = source.getPool();
        PooledMessage msg = pool.acquire();
        msg.value = "twr-named";
        source.publish(msg);

        long deadline = System.currentTimeMillis() + 500;
        while (pool.availableCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, pool.availableCount(), "message should be returned using try-with-resources (named)");
    }
}
