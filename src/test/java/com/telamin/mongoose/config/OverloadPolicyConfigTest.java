/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.config;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.dispatch.FeedOverloadSettings;
import com.telamin.mongoose.dispatch.OverloadPolicy;
import com.telamin.mongoose.dispatch.OverloadSettings;
import com.telamin.mongoose.exception.ConfigurationException;
import com.telamin.mongoose.service.EventSource;
import org.agrona.concurrent.IdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Configuration of the overload policy (telaminai/mongoose#79): YAML, builders, validation and boot resolution. */
@Timeout(30)
@SuppressWarnings("deprecation")
class OverloadPolicyConfigTest {

    private static MongooseServerConfig load(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setTagInspector(tag -> true);
        return new Yaml(options).loadAs(new StringReader(yaml), MongooseServerConfig.class);
    }

    @Test
    void yamlCarriesEveryKeyForFeedsAndPipes() {
        MongooseServerConfig cfg = load("""
                eventFeeds:
                  - name: orders
                    instance: !!com.telamin.mongoose.connector.memory.InMemoryEventSource { }
                    overload:
                      policy: BLOCK_WITH_DEADLINE
                      blockDeadlineMillis: 250
                      spinBoundMillis: 2
                      subscribers:
                        telemetry:
                          policy: DROP_AFTER_SPIN
                          spinBoundMillis: 0
                pipes:
                  - name: fills
                    overload:
                      policy: DROP_AFTER_SPIN
                      spinBoundMillis: 5
                """);
        FeedOverloadSettings orders = cfg.getEventFeeds().getFirst().resolveOverload();
        assertEquals(new OverloadSettings(OverloadPolicy.BLOCK_WITH_DEADLINE, TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.MILLISECONDS.toNanos(250)), orders.feedDefault());
        assertEquals(new OverloadSettings(OverloadPolicy.DROP_AFTER_SPIN, 0, 0), orders.forGroup("telemetry"));
        assertEquals(orders.feedDefault(), orders.forGroup("risk"), "a group without an override uses the feed's");
        FeedOverloadSettings fills = cfg.getPipes().getFirst().resolveOverload();
        assertEquals(new OverloadSettings(OverloadPolicy.DROP_AFTER_SPIN, TimeUnit.MILLISECONDS.toNanos(5), 0),
                fills.feedDefault());
    }

    @Test
    void builderAndProgrammaticFormsMatchYaml() {
        var feed = EventFeedConfig.builder().name("orders")
                .overload(OverloadPolicyConfig.blockWithDeadline(Duration.ofMillis(250))
                        .subscriber("telemetry", OverloadPolicyConfig.dropAfterSpin(Duration.ZERO)))
                .build();
        FeedOverloadSettings s = feed.resolveOverload();
        assertEquals(OverloadSettings.blockWithDeadline(Duration.ofMillis(250)), s.feedDefault());
        assertEquals(OverloadSettings.dropAfterSpin(Duration.ZERO), s.forGroup("telemetry"));
        var pipe = HandlerPipeConfig.builder().name("fills")
                .overload(OverloadPolicyConfig.dropAfterSpin(Duration.ofMillis(3))).build();
        assertEquals(OverloadSettings.dropAfterSpin(Duration.ofMillis(3)), pipe.resolveOverload().feedDefault());
    }

    @Test
    void noExplicitPolicyIsTheDefaultWithABootWarningNamingTheFeed() {
        try (Captured log = new Captured()) {
            assertEquals(FeedOverloadSettings.DEFAULT, EventFeedConfig.builder().name("prices").build().resolveOverload());
            assertEquals(FeedOverloadSettings.DEFAULT, load("""
                    eventFeeds:
                      - name: ticks
                    """).getEventFeeds().getFirst().resolveOverload());
            assertTrue(log.contains("feed 'prices' has no explicit overload policy"), log.messages());
            assertTrue(log.contains("feed 'ticks' has no explicit overload policy"), log.messages());
        }
    }

    @Test
    void slowConsumerStrategyBackoffMapsToDropAfterSpinWithAWarning() {
        try (Captured log = new Captured()) {
            var cfg = EventFeedConfig.builder().name("md").slowConsumerStrategy(EventSource.SlowConsumerStrategy.BACKOFF).build();
            assertEquals(FeedOverloadSettings.DEFAULT, cfg.resolveOverload());
            assertTrue(log.contains("feed 'md': slowConsumerStrategy BACKOFF is deprecated and means DROP_AFTER_SPIN"),
                    log.messages());
            assertEquals(FeedOverloadSettings.DEFAULT, load("""
                    pipes:
                      - name: p
                        slowConsumerStrategy: BACKOFF
                    """).getPipes().getFirst().resolveOverload());
        }
    }

    @Test
    void unimplementedSlowConsumerStrategiesFailBootNamingTheFeed() {
        for (EventSource.SlowConsumerStrategy s : List.of(EventSource.SlowConsumerStrategy.DISCONNECT,
                EventSource.SlowConsumerStrategy.EXIT_PROCESS)) {
            var feed = EventFeedConfig.builder().name("orders").slowConsumerStrategy(s).build();
            var e = assertThrows(ConfigurationException.class, feed::resolveOverload);
            assertTrue(e.getMessage().contains("feed 'orders'") && e.getMessage().contains(s.name()), e.getMessage());
            var pipe = HandlerPipeConfig.builder().name("fills").slowConsumerStrategy(s).build();
            assertThrows(ConfigurationException.class, pipe::resolveOverload);
        }
        // and through the YAML boot: the server never starts
        var e = assertThrows(ConfigurationException.class, () -> MongooseServer.bootServer(new StringReader("""
                eventFeeds:
                  - name: orders
                    instance: !!com.telamin.mongoose.connector.memory.InMemoryEventSource { }
                    slowConsumerStrategy: DISCONNECT
                """), rec -> { }));
        assertTrue(e.getMessage().contains("feed 'orders'"), e.getMessage());
    }

    @Test
    void anOverloadBlockWinsOverAnExplicitSlowConsumerStrategy() {
        var feed = EventFeedConfig.builder().name("orders").slowConsumerStrategy(EventSource.SlowConsumerStrategy.DISCONNECT)
                .overload(OverloadPolicyConfig.blockWithDeadline(Duration.ofMillis(100))).build();
        assertEquals(OverloadPolicy.BLOCK_WITH_DEADLINE, feed.resolveOverload().feedDefault().policy());
    }

    @Test
    void badValuesFailNamingTheFeedAndTheKey() {
        assertBad("overload.policy is required", new OverloadPolicyConfig());
        assertBad("overload.blockDeadlineMillis is required for BLOCK_WITH_DEADLINE",
                cfg(OverloadPolicy.BLOCK_WITH_DEADLINE, null, null));
        assertBad("overload.blockDeadlineMillis is required for BLOCK_WITH_DEADLINE",
                cfg(OverloadPolicy.BLOCK_WITH_DEADLINE, 0L, null));
        assertBad("overload.blockDeadlineMillis applies only to BLOCK_WITH_DEADLINE",
                cfg(OverloadPolicy.DROP_AFTER_SPIN, 10L, null));
        assertBad("overload.spinBoundMillis must be >= 0", cfg(OverloadPolicy.DROP_AFTER_SPIN, null, -1L));
        assertBad("overload.subscribers.t.blockDeadlineMillis is required",
                cfg(OverloadPolicy.DROP_AFTER_SPIN, null, null).subscriber("t", cfg(OverloadPolicy.BLOCK_WITH_DEADLINE, null, null)));
        assertBad("may not nest subscribers", cfg(OverloadPolicy.DROP_AFTER_SPIN, null, null)
                .subscriber("t", cfg(OverloadPolicy.DROP_AFTER_SPIN, null, null).subscriber("u", cfg(OverloadPolicy.DROP_AFTER_SPIN, null, null))));
        var yaml = assertThrows(Exception.class, () -> load("""
                eventFeeds:
                  - name: orders
                    overload:
                      policy: BLOCK
                """));
        assertTrue(String.valueOf(yaml.getMessage()).contains("BLOCK"), yaml.getMessage());
    }

    @Test
    void configuredPolicyReachesTheFeedPublisherAtBoot() throws Exception {
        var source = new CapturingSource();
        var config = MongooseServerConfig.builder()
                .addEventFeed(EventFeedConfig.builder().instance(source).name("orders")
                        .overload(OverloadPolicyConfig.blockWithDeadline(Duration.ofMillis(250))
                                .subscriber("telemetry", OverloadPolicyConfig.dropAfterSpin(Duration.ZERO)))
                        .build())
                .build();
        MongooseServer server = MongooseServer.bootServer(config, rec -> { });
        try {
            assertNotNull(source.publisher(), "the source was registered");
            FeedOverloadSettings applied = source.publisher().overload();
            assertEquals(OverloadSettings.blockWithDeadline(Duration.ofMillis(250)), applied.feedDefault());
            assertEquals(OverloadSettings.dropAfterSpin(Duration.ZERO), applied.forGroup("telemetry"));
        } finally {
            server.stop();
        }
    }

    @Test
    void blockingPipeIsWarnedAtBootAndAlsoWithCacheEventLog() {
        try (Captured log = new Captured()) {
            HandlerPipeConfig.builder().name("fills")
                    .overload(OverloadPolicyConfig.blockWithDeadline(Duration.ofMillis(100))).build().resolveOverload();
            assertTrue(log.contains("pipe 'fills': BLOCK_WITH_DEADLINE waits only for publishers on non-agent threads"),
                    log.messages());
            assertFalse(log.contains("pipe 'fills': cacheEventLog"), log.messages());
            HandlerPipeConfig.builder().name("cached").cacheEventLog(true)
                    .overload(OverloadPolicyConfig.blockWithDeadline(Duration.ofMillis(100))).build().resolveOverload();
            assertTrue(log.contains("pipe 'cached': cacheEventLog with BLOCK_WITH_DEADLINE"), log.messages());
            HandlerPipeConfig.builder().name("plain")
                    .overload(OverloadPolicyConfig.dropAfterSpin(Duration.ofMillis(1))).build().resolveOverload();
            assertFalse(log.contains("pipe 'plain'"), log.messages());
        }
    }

    @Test
    void deadlineAboveSixtySecondsIsWarnedNotCapped() {
        try (Captured log = new Captured()) {
            var s = EventFeedConfig.builder().name("orders")
                    .overload(OverloadPolicyConfig.blockWithDeadline(Duration.ofMillis(60_001))).build().resolveOverload();
            assertEquals(TimeUnit.MILLISECONDS.toNanos(60_001), s.feedDefault().blockDeadlineNanos());
            assertTrue(log.contains("blockDeadlineMillis 60001 exceeds 60000 ms"), log.messages());
            EventFeedConfig.builder().name("ok")
                    .overload(OverloadPolicyConfig.blockWithDeadline(Duration.ofMillis(60_000))).build().resolveOverload();
            assertFalse(log.contains("blockDeadlineMillis 60000 exceeds"), log.messages());
        }
    }

    @Test
    void overrideForAnUnknownGroupIsWarnedAtBootAndAfterStart() throws Exception {
        var source = new CapturingSource();
        var config = MongooseServerConfig.builder()
                .addEventFeed(EventFeedConfig.builder().instance(source).name("orders")
                        .overload(OverloadPolicyConfig.blockWithDeadline(Duration.ofMillis(250))
                                .subscriber("telemtry", OverloadPolicyConfig.dropAfterSpin(Duration.ZERO)))
                        .build())
                .build();
        try (Captured boot = new Captured(com.telamin.mongoose.internal.ServerConfigurator.class);
             Captured started = new Captured(MongooseServer.class)) {
            MongooseServer server = MongooseServer.bootServer(config, rec -> { });
            try {
                assertTrue(boot.contains("feed 'orders': overload override for subscriber group 'telemtry', which is "
                        + "not a configured processor group"), boot.messages());
                assertEquals(1, started.messages.stream().filter(m -> m.contains(
                        "feed 'orders': overload override for subscriber group 'telemtry' matches no processor group"))
                        .count(), started.messages());
            } finally {
                server.stop();
            }
        }
    }

    @Test
    void quietPeriodAndKillSwitchAreFeedLevelKeys() {
        FeedOverloadSettings s = load("""
                eventFeeds:
                  - name: orders
                    overload:
                      policy: BLOCK_WITH_DEADLINE
                      blockDeadlineMillis: 100
                      quietPeriodMillis: 5000
                      killSwitch: true
                """).getEventFeeds().getFirst().resolveOverload();
        assertEquals(TimeUnit.MILLISECONDS.toNanos(5000), s.quietPeriodNanos());
        assertTrue(s.killSwitchOnLoss());
        FeedOverloadSettings defaults = EventFeedConfig.builder().name("d")
                .overload(OverloadPolicyConfig.dropAfterSpin(Duration.ofMillis(1))).build().resolveOverload();
        assertEquals(FeedOverloadSettings.DEFAULT_QUIET_PERIOD_NANOS, defaults.quietPeriodNanos());
        assertFalse(defaults.killSwitchOnLoss(), "the kill switch is off by default");
        assertBad("may not set quietPeriodMillis or killSwitch", cfg(OverloadPolicy.DROP_AFTER_SPIN, null, null)
                .subscriber("t", cfg(OverloadPolicy.DROP_AFTER_SPIN, null, null).killSwitch(true)));
        assertBad("overload.quietPeriodMillis must be >= 1000", cfg(OverloadPolicy.DROP_AFTER_SPIN, null, null)
                .quietPeriod(Duration.ofMillis(999)));
    }

    @Test
    void killSwitchEnabledWithoutAHookIsWarnedAfterStartAndServerExposesStatusAndClear() throws Exception {
        var source = new CapturingSource();
        var config = MongooseServerConfig.builder()
                .addEventFeed(EventFeedConfig.builder().instance(source).name("orders")
                        .overload(OverloadPolicyConfig.blockWithDeadline(Duration.ofMillis(250)).killSwitch(true))
                        .build())
                .build();
        try (Captured started = new Captured(MongooseServer.class)) {
            MongooseServer server = MongooseServer.bootServer(config, rec -> { });
            try {
                assertTrue(started.contains("overload kill switch enabled for feeds [orders] but no hook is set"),
                        started.messages());
                assertTrue(server.overloadStates().containsKey("orders"));
                assertEquals(0, server.clearOverloadState("orders"));
                assertEquals(-1, server.clearOverloadState("nope"));
            } finally {
                server.stop();
            }
        }
    }

    @Test
    void preOverloadAllArgumentConstructorsAreKept() throws Exception {
        assertNotNull(EventFeedConfig.class.getConstructor(Object.class, String.class, boolean.class, boolean.class,
                EventSource.EventWrapStrategy.class, EventSource.SlowConsumerStrategy.class, Function.class, String.class,
                IdleStrategy.class));
        assertNotNull(HandlerPipeConfig.class.getConstructor(String.class, String.class, boolean.class, boolean.class,
                boolean.class, EventSource.SlowConsumerStrategy.class, Function.class, String.class, IdleStrategy.class,
                boolean.class));
        assertNotNull(HandlerPipeConfig.class.getConstructor(String.class, String.class, boolean.class, boolean.class,
                boolean.class, EventSource.SlowConsumerStrategy.class, Function.class, String.class, IdleStrategy.class));
    }

    private static OverloadPolicyConfig cfg(OverloadPolicy policy, Long deadline, Long spin) {
        var c = new OverloadPolicyConfig();
        c.setPolicy(policy);
        c.setBlockDeadlineMillis(deadline);
        c.setSpinBoundMillis(spin);
        return c;
    }

    private static void assertBad(String expected, OverloadPolicyConfig overload) {
        var feed = EventFeedConfig.builder().name("orders").overload(overload).build();
        var e = assertThrows(ConfigurationException.class, feed::resolveOverload);
        assertTrue(e.getMessage().contains("feed 'orders'") && e.getMessage().contains(expected), e.getMessage());
    }

    static final class CapturingSource extends com.telamin.mongoose.service.extension.AbstractEventSourceService<Object> {
        CapturingSource() {
            super("orders");
        }

        EventToQueuePublisher<Object> publisher() {
            return output;
        }
    }

    static final class Captured extends Handler implements AutoCloseable {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final Logger logger;

        Captured() {
            this(OverloadPolicyConfig.class);
        }

        Captured(Class<?> type) {
            logger = Logger.getLogger(type.getName());
            logger.addHandler(this);
        }

        boolean contains(String text) {
            return messages.stream().anyMatch(m -> m.contains(text));
        }

        String messages() {
            return String.valueOf(messages);
        }

        @Override
        public void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }
}
