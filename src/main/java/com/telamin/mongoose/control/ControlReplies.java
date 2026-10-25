/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Completion isolation (design "Agent threads and replies"): replies are completed on a group's agent thread, inside
 * the control processor's batch, so a caller's continuation must never run there, where it could block the group or
 * re-enter it. The stages handed to callers are completed from this executor instead.
 */
public final class ControlReplies {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mongoose-control-replies");
        t.setDaemon(true);
        return t;
    });

    private ControlReplies() {
    }

    /** The request's reply as a stage whose dependents never run on an agent thread. */
    public static CompletionStage<ControlReply> isolated(ControlRequest request) {
        CompletableFuture<ControlReply> out = new CompletableFuture<>();
        request.reply().whenCompleteAsync((reply, error) -> {
            if (error != null) out.completeExceptionally(error);
            else out.complete(reply);
        }, EXECUTOR);
        return out;
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
