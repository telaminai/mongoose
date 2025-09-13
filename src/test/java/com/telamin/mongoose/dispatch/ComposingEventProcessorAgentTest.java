/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import com.telamin.mongoose.test.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

public class ComposingEventProcessorAgentTest {

    @Test
    public void onlySubscribedHandlerSeesEvents() throws Exception {
        EventFlowManager eventFlowManager = new EventFlowManager();
        TestHeartBeatFeed testEventFeed = new TestHeartBeatFeed("testEventSource");
        testEventFeed.setEventFlowManager(eventFlowManager, "testEventFeed");

        eventFlowManager.init();

        SubscriptionEventHandler subscriptionEventHandler = new SubscriptionEventHandler();
        TestEventProcessor testEventProcessor1 = new TestEventProcessor(subscriptionEventHandler);
        testEventProcessor1.init();

        NoSubscriptionEventHandler noSubscriptionEventHandler = new NoSubscriptionEventHandler();
        TestEventProcessor testEventProcessor2 = new TestEventProcessor(noSubscriptionEventHandler);
        testEventProcessor2.init();

        ComposingEventProcessorAgent agent = new ComposingEventProcessorAgent("",
                eventFlowManager,
                null,
                new DeadWheelScheduler(),
                new ConcurrentHashMap<>());

        agent.addNamedEventProcessor(() -> new NamedEventProcessor("myProcessor1", testEventProcessor1));
        agent.addNamedEventProcessor(() -> new NamedEventProcessor("myProcessor2", testEventProcessor2));

        agent.onStart();
        agent.doWork();

        Assertions.assertFalse(subscriptionEventHandler.isInvoked());
        Assertions.assertFalse(noSubscriptionEventHandler.isInvoked());

        testEventFeed.fireHeartbeatEvent(new HeartbeatEvent());
        agent.doWork();

        Assertions.assertTrue(subscriptionEventHandler.isInvoked());
        Assertions.assertFalse(noSubscriptionEventHandler.isInvoked());
    }

    @Test
    public void twoSubscribedHandlerSeesEvents() throws Exception {
        EventFlowManager eventFlowManager = new EventFlowManager();
        TestHeartBeatFeed testEventFeed = new TestHeartBeatFeed("testEventSource");
        testEventFeed.setEventFlowManager(eventFlowManager, "testEventFeed");

        eventFlowManager.init();

        SubscriptionEventHandler subscriptionEventHandler = new SubscriptionEventHandler();
        TestEventProcessor testEventProcessor1 = new TestEventProcessor(subscriptionEventHandler);
        testEventProcessor1.init();

        SubscriptionEventHandler noSubscriptionEventHandler = new SubscriptionEventHandler();
        TestEventProcessor testEventProcessor2 = new TestEventProcessor(noSubscriptionEventHandler);
        testEventProcessor2.init();

        ComposingEventProcessorAgent agent = new ComposingEventProcessorAgent("",
                eventFlowManager,
                null,
                new DeadWheelScheduler(),
                new ConcurrentHashMap<>());

        agent.addNamedEventProcessor(() -> new NamedEventProcessor("myProcessor1", testEventProcessor1));
        agent.addNamedEventProcessor(() -> new NamedEventProcessor("myProcessor2", testEventProcessor2));

        agent.onStart();
        agent.doWork();

        Assertions.assertFalse(subscriptionEventHandler.isInvoked());
        Assertions.assertFalse(noSubscriptionEventHandler.isInvoked());

        testEventFeed.fireHeartbeatEvent(new HeartbeatEvent());
        agent.doWork();

        Assertions.assertTrue(subscriptionEventHandler.isInvoked());
        Assertions.assertTrue(noSubscriptionEventHandler.isInvoked());
    }

    @Test
    public void twoSubscribedWithQualifierHandlerSeesEvents() throws Exception {
        EventFlowManager eventFlowManager = new EventFlowManager();
        TestHeartBeatFeed testEventFeed = new TestHeartBeatFeed("testEventSource");
        testEventFeed.setEventFlowManager(eventFlowManager, "testEventFeed");

        eventFlowManager.init();

        SubscriptionEventHandler subscriptionEventHandler = new SubscriptionEventHandler("qualifier-1");
        TestEventProcessor testEventProcessor1 = new TestEventProcessor(subscriptionEventHandler);
        testEventProcessor1.init();

        SubscriptionEventHandler noSubscriptionEventHandler = new SubscriptionEventHandler("qualifier-2");
        TestEventProcessor testEventProcessor2 = new TestEventProcessor(noSubscriptionEventHandler);
        testEventProcessor2.init();

        ComposingEventProcessorAgent agent = new ComposingEventProcessorAgent("",
                eventFlowManager,
                null,
                new DeadWheelScheduler(),
                new ConcurrentHashMap<>());

        agent.addNamedEventProcessor(() -> new NamedEventProcessor("myProcessor1", testEventProcessor1));
        agent.addNamedEventProcessor(() -> new NamedEventProcessor("myProcessor2", testEventProcessor2));

        agent.onStart();
        agent.doWork();

        Assertions.assertFalse(subscriptionEventHandler.isInvoked());
        Assertions.assertFalse(noSubscriptionEventHandler.isInvoked());

        testEventFeed.fireHeartbeatEvent(new HeartbeatEvent());
        agent.doWork();

        Assertions.assertTrue(subscriptionEventHandler.isInvoked());
        Assertions.assertTrue(noSubscriptionEventHandler.isInvoked());
    }
}
