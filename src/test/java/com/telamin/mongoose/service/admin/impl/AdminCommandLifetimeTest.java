package com.telamin.mongoose.service.admin.impl;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One invocation's lifetime at its edges (#48 correction, N3), through a real publisher, queue and invoker, with an agent
 * thread the test drives. Written with the fix: the race below needs its seam, which b4e80c1 does not have.
 */
class AdminCommandLifetimeTest {

    static final String BOUND = "mongoose.admin.completionTimeoutMs";

    @AfterEach
    void reset() {
        System.clearProperty(BOUND);
        AdminCommand.beforeExpiry = c -> { };
    }

    /** A processor that runs a command's action as its cycle, and nothing else. */
    static DataFlow processor() {
        return (DataFlow) Proxy.newProxyInstance(DataFlow.class.getClassLoader(), new Class<?>[]{DataFlow.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "runInEventCycle" -> {
                        ((Runnable) args[1]).run();
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "DEMO-processor";
                    default -> null;
                });
    }

    /** The agent: drains the queue into the invoker until stopped. */
    static Thread agent(OneToOneConcurrentArrayQueue<Object> queue, AtomicBoolean running) {
        AdminCommandInvoker invoker = new AdminCommandInvoker();
        DataFlow processor = processor();
        Thread t = new Thread(() -> {
            while (running.get()) {
                Object item = queue.poll();
                if (item == null) Thread.onSpinWait();
                else invoker.dispatchEvent(item, processor);
            }
        }, "DEMO-agent");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "latch");
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aCommandThatCompletesJustAsTheWaitEnds_isItsOutcome_notAnAbandonment() throws Exception {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(64);
        EventToQueuePublisher<AdminCommand> publisher = new EventToQueuePublisher<>("DEMO-admin");
        publisher.addTargetQueue(queue, "DEMO-queue");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AdminCommand template = new AdminCommand((args, out, err) -> {
            started.countDown();
            await(release);
            out.accept("DEMO-done");
        }, publisher);
        List<Object> replies = new CopyOnWriteArrayList<>();
        template.setOutput(replies::add);
        template.setErrOutput(o -> replies.add("ERR " + o));
        AtomicBoolean running = new AtomicBoolean(true);
        Thread agent = agent(queue, running);
        try {
            System.setProperty(BOUND, "200");
            // the wait has ended; before it decides, the command completes: the phase settles the race
            AdminCommand.beforeExpiry = invocation -> {
                release.countDown();
                try {
                    assertTrue(invocation.getDone().await(5, TimeUnit.SECONDS), "it completed");
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            };
            template.publishCommand(List.of("DEMO.cmd"));
            assertEquals(List.of("DEMO-done"), replies, "completed is the outcome: no 'started' message after its reply");
        } finally {
            release.countDown();
            running.set(false);
            agent.join(5_000);
        }
    }

    @Test
    void aReplyThatHasNotBegunWhenTheCallerGivesUp_isSuppressed() throws Exception {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(64);
        EventToQueuePublisher<AdminCommand> publisher = new EventToQueuePublisher<>("DEMO-admin");
        publisher.addTargetQueue(queue, "DEMO-queue");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), replied = new CountDownLatch(1);
        AdminCommand template = new AdminCommand((args, out, err) -> {
            started.countDown();
            await(release);
            out.accept("DEMO-late");                              // begins only after the caller has given up
            replied.countDown();
        }, publisher);
        List<Object> replies = new CopyOnWriteArrayList<>();
        template.setOutput(replies::add);
        template.setErrOutput(o -> replies.add("ERR " + o));
        AtomicBoolean running = new AtomicBoolean(true);
        Thread agent = agent(queue, running);
        try {
            System.setProperty(BOUND, "200");
            template.publishCommand(List.of("DEMO.cmd"));         // returns at the bound: the command is running
            assertEquals(1, replies.size(), replies.toString());
            assertTrue(replies.get(0).toString().contains("started"), "told it started: " + replies);
            release.countDown();
            await(replied);
            assertFalse(replies.contains("DEMO-late"), "a reply not begun when the channel closed does not reach the caller");
        } finally {
            release.countDown();
            running.set(false);
            agent.join(5_000);
        }
    }
}
