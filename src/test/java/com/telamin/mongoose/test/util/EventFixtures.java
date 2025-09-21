/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.test.util;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.extension.AbstractEventSourceService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Common fixtures for event processing tests.
 */
public final class EventFixtures {

    private EventFixtures() {
    }

    /**
     * Simple log listener capturing a bounded number of records.
     */
    public static class CapturingLogListener implements LogRecordListener {
        private final int max;
        private final List<LogRecord> records = new ArrayList<>();

        public CapturingLogListener(int max) {
            this.max = max;
        }

        @Override
        public void processLogRecord(LogRecord logRecord) {
            if (records.size() < max) records.add(logRecord);
        }

        public List<LogRecord> records() {
            return records;
        }
    }

    /**
     * Wires a single source and processor under unique names and boots the server.
     * Returns a handle with convenient methods for publishing and awaiting.
     */
    public static <E, S extends EventSourceStub<E>, P extends DataFlow>
    Harness<E, S, P> bootOneSourceOneProcessor(S source,
                                               String feedBaseName,
                                               P processor,
                                               String processorNameBase) {
        String feedName = (source.getName() != null && !source.getName().isEmpty()) ? source.getName() : TestNameUtil.unique(feedBaseName);
        String procName = TestNameUtil.unique(processorNameBase);
        EventFeedConfig<S> feed = EventFeedConfig.<S>builder()
                .instance(source)
                .name(feedName)
                .broadcast(true)
                .wrapWithNamedEvent(false)
                .build();
        EventProcessorConfig<P> epCfg = EventProcessorConfig.<P>builder()
                .handler(processor)
                .build();
        EventProcessorGroupConfig group = EventProcessorGroupConfig.builder()
                .agentName("group-" + processorNameBase)
                .put(procName, epCfg)
                .build();
        MongooseServerConfig app = MongooseServerConfig.builder()
                .addEventFeed(feed)
                .addProcessorGroup(group)
                .build();
        CapturingLogListener logs = new CapturingLogListener(1000);
        MongooseServer server = MongooseServer.bootServer(app, logs);
        return new Harness<>(server, source, processor, feedName, logs);
    }

    /**
     * A simple event source stub usable in tests.
     */
    public static abstract class EventSourceStub<E> extends AbstractEventSourceService<E> {
        protected EventSourceStub(String name) {
            super(name);
        }

        public void publish(E event) {
            if (output != null) output.publish(event);
        }
    }

    /**
     * A simple processor stub with latch-based assertions.
     */
    public static abstract class LatchingProcessor<P extends LatchingProcessor<P>> implements DataFlow {
        protected final CountDownLatch latch;
        protected final List<EventFeed> feeds = new ArrayList<>();
        protected volatile Object last;

        public LatchingProcessor(CountDownLatch latch) {
            this.latch = latch;
        }

        public Object last() {
            return last;
        }

        @Override
        public void addEventFeed(EventFeed feed) {
            feeds.add(feed);
        }

        @Override
        public void start() {
            EventSubscriptionKey<Object> key = EventSubscriptionKey.onEvent(feedName());
            feeds.forEach(f -> f.subscribe(this, key));
        }

        /**
         * Provide feed name to subscribe to.
         */
        protected abstract String feedName();
    }

    public static final class Harness<E, S extends EventSourceStub<E>, P extends DataFlow> {
        public final MongooseServer server;
        public final S source;
        public final P processor;
        public final String feedName;
        public final CapturingLogListener logs;

        Harness(MongooseServer s, S src, P p, String f, CapturingLogListener l) {
            this.server = s;
            this.source = src;
            this.processor = p;
            this.feedName = f;
            this.logs = l;
        }

        public boolean await(CountDownLatch latch, long timeoutMs) throws InterruptedException {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        public void stop() {
            server.stop();
        }
    }
}
