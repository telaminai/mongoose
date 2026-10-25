/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Completion isolation (design "Agent threads and replies"): replies are completed on a group's agent thread, inside
 * the control processor's batch. The stages handed to callers are completed from this executor instead, so an
 * already-attached dependent does not run on the completing agent. A synchronous dependent attached after completion
 * may run on the attaching thread; callers must use asynchronous continuations for blocking work.
 */
public final class ControlReplies {

    /** For stages made without a server (a group driven directly). A server owns its own, see {@link #newExecutor}. */
    private static final ExecutorService DEFAULT = newExecutor();

    private ControlReplies() {
    }

    /**
     * An elastic pool of daemon threads named {@code mongoose-control-replies}: a dependent that blocks, even on another
     * isolated stage, holds only its own thread, so it can never stall the completion it waits for (review of PR 3: a
     * single thread deadlocked on exactly that).
     */
    public static ExecutorService newExecutor() {
        AtomicInteger n = new AtomicInteger();
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mongoose-control-replies-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** The request's reply as a stage completed off the group's agent thread. */
    public static CompletionStage<ControlReply> isolated(ControlRequest request) {
        return isolated(request, DEFAULT);
    }

    /** As {@link #isolated(ControlRequest)}, completed on {@code executor}; on a fresh thread once it is shut down. */
    public static CompletionStage<ControlReply> isolated(ControlRequest request, Executor executor) {
        CompletableFuture<ControlReply> out = new CompletableFuture<>();
        Executor safe = task -> executeSafely(executor, task);
        request.reply().whenCompleteAsync((reply, error) -> {
            if (error != null) out.completeExceptionally(error);
            else out.complete(reply);
        }, safe);
        return out;
    }

    /** Dispatch completion or cleanup even if server shutdown already closed the reply executor. */
    public static void executeSafely(Executor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException shutDown) {
            Thread t = new Thread(task, "mongoose-control-replies-late");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Wait for a reply from an ordinary thread, at most {@code timeoutMillis}. Never called on an agent thread.
     *
     * @return the reply, or null if it did not come in time (the request still applies: a timeout detaches)
     */
    public static ControlReply await(ControlRequest request, long timeoutMillis) {
        if (AgentThreads.isAgentThread()) {
            throw new IllegalStateException("an agent thread never waits for a control reply");
        }
        try {
            return request.reply().get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("control reply failed", e.getCause());
        }
    }
}
