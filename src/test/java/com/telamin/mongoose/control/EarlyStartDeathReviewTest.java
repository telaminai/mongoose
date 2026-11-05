/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control;

import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class EarlyStartDeathReviewTest {
    @Test void failureBeforeThreadCaptureStillAnswersAcceptedRequest() throws Exception {
        var group = new ComposingEventProcessorAgent("early-start-death", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        var add = group.addNamedEventProcessor(() -> new NamedEventProcessor("p", new RepliesAndServiceLockReviewTest.Proc()),
                new ProcessorRegistration("p"));
        Logger logger = Logger.getLogger(ComposingEventProcessorAgent.class.getName());
        Level oldLevel = logger.getLevel();
        Handler failedSink = new Handler() {
            public void publish(LogRecord r) {
                if (r.getMessage().equals("onStart early-start-death")) throw new IllegalStateException("logging sink failed");
            }
            public void flush() { }
            public void close() { }
        };
        logger.setLevel(Level.ALL);
        logger.addHandler(failedSink);
        var runner = new AgentRunner(new BusySpinIdleStrategy(), t -> {
            throw new IllegalStateException("error handler failed", t);
        }, null, group);
        Thread worker = new Thread(runner, "early-start-death");
        worker.setUncaughtExceptionHandler((t, failure) -> { });
        try {
            worker.start(); worker.join(2000);
            assertFalse(worker.isAlive());
            assertEquals(com.telamin.mongoose.control.event.ReplyOutcome.NOT_RUNNING,
                    add.reply().get(2, TimeUnit.SECONDS).outcome(),
                    "an accepted add must be answered without another sender");
            assertEquals(ControlDriver.GroupState.DEAD, group.control().state());
            assertEquals(0, group.control().outstanding());
        } finally {
            logger.removeHandler(failedSink);
            logger.setLevel(oldLevel);
            runner.close();
            group.onClose();
        }
    }
}
