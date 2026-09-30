/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.admin.impl;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.AdminFunction;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Encapsulates an administrative command invocation that can be published to an
 * EventToQueuePublisher for asynchronous execution or executed directly.
 * Holds output/error consumers and the target AdminFunction implementation.
 */
@Experimental
@Data
@AllArgsConstructor
public class AdminCommand {
    private Consumer<Object> output;
    private Consumer<Object> errOutput;
    private AdminFunction<Object, Object> commandWithOutput;
    private final EventToQueuePublisher<AdminCommand> targetQueue;
    private final Semaphore semaphore = new Semaphore(1);
    private transient List<String> args;
    /** Option A: delivered to the processor as a {@code Signal} in an event cycle, not run as a lambda. */
    private boolean signalRouted;
    /** Set when this command starts to execute (one instance per request), so a retried dispatch does not re-run it. */
    private volatile boolean executed;
    /**
     * The name the command was REGISTERED under, bound once (#48 review, finding 4): a request's own spelling (" x ") was
     * trimmed for lookup but carried into the signal's filter, so no handler answered. Null for a command built without
     * registration, which then keeps the request's name.
     */
    private String name;

    // ---- one request's lifetime (#48 review, finding 3): QUEUED until its processor claims it, or its caller cancels ----
    static final int QUEUED = 0, CLAIMED = 1, CANCELLED = 2;
    private final java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger(QUEUED);
    /** Counted down once, when the command completes or is refused: the caller's wait. */
    private volatile java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    /** The reply channel: open until the caller has its answer, then closed once, so a late reply is dropped. */
    private boolean open = true;
    /** How long a caller waits for its command to complete; see {@link #completionTimeoutMs()}. */
    static final long DEFAULT_COMPLETION_TIMEOUT_MS = 10_000;

    /** A signal-routed command: no lambda; the processor's own handler does the work, in an event cycle. */
    public AdminCommand(EventToQueuePublisher<AdminCommand> targetQueue, boolean signalRouted) {
        this.commandWithOutput = null;
        this.output = System.out::println;
        this.errOutput = System.err::println;
        this.targetQueue = targetQueue;
        this.signalRouted = signalRouted;
    }

    /**
     * Create an AdminCommand that will publish to a target queue for asynchronous execution.
     *
     * @param commandWithOutput the admin function to execute
     * @param targetQueue       the queue publisher to post this command to
     */
    public AdminCommand(AdminFunction<Object, Object> commandWithOutput, EventToQueuePublisher<AdminCommand> targetQueue) {
        this.commandWithOutput = commandWithOutput;
        this.output = System.out::println;
        this.errOutput = System.err::println;
        this.targetQueue = targetQueue;
    }

    /**
     * Create an AdminCommand that executes the command directly in the caller thread.
     *
     * @param commandWithOutput the admin function to execute
     */
    public AdminCommand(AdminFunction<Object, Object> commandWithOutput) {
        this.commandWithOutput = commandWithOutput;
        this.output = System.out::println;
        this.errOutput = System.err::println;
        this.targetQueue = null;
    }

    /**
     * Copy constructor used to bind a request to an existing command template.
     *
     * @param adminCommand         the source command to copy function and targetQueue from
     * @param adminCommandRequest  the request providing output consumers and arguments
     */
    public AdminCommand(AdminCommand adminCommand, AdminCommandRequest adminCommandRequest) {
        this.commandWithOutput = adminCommand.commandWithOutput;
        this.targetQueue = adminCommand.targetQueue;
        this.output = adminCommandRequest.getOutput();
        this.errOutput = adminCommandRequest.getErrOutput();
        this.args = new ArrayList<>(adminCommandRequest.getArguments());
        this.name = adminCommand.name;
        this.args.add(0, adminCommand.name != null ? adminCommand.name : adminCommandRequest.getCommand());
        this.signalRouted = adminCommand.signalRouted;
    }

    /**
     * Publish the supplied admin request to the target queue or execute directly if no queue.
     *
     * @param adminCommandRequest the request containing command name, args and output consumers
     */
    public void publishCommand(AdminCommandRequest adminCommandRequest) {
        AdminCommand adminCommand = new AdminCommand(this, adminCommandRequest);
        adminCommand.publishCommand(adminCommand.args);
    }

    /**
     * Publish the supplied argument list to the target queue or execute directly.
     *
     * @param value the command arguments including the command name as first element
     */
    public void publishCommand(List<String> value) {
        if (targetQueue == null) {
            commandWithOutput.processAdminCommand(value, output, errOutput);
            return;
        }
        boolean admitted;
        try {
            admitted = semaphore.tryAcquire(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new com.telamin.mongoose.exception.AdminCommandException("Interrupted while publishing admin command", e);
        }
        if (!admitted) {
            output.accept("command is busy try again");
            return;
        }
        try {
            // each publish is a new execution: its retries share these, and a reused template starts afresh (F6)
            executed = false;
            state.set(QUEUED);
            done = new java.util.concurrent.CountDownLatch(1);
            synchronized (this) {
                open = true;
            }
            args = value;
            targetQueue.publish(this);
            awaitOutcome();
        } finally {
            semaphore.release();
        }
    }

    /**
     * Wait for this command's outcome, bounded (#48 review, finding 3: the wait was unbounded, so a caller of a stopped
     * server, or of a processor muted for a replay, waited forever, and an interrupted caller left its command to run
     * later and reply to a channel nobody read). On timeout or interrupt, work its processor has NOT claimed is cancelled:
     * it never runs later, and the caller is told so. Work it has claimed has started: that cannot be cancelled, so the
     * caller is told it started and may still complete, and its reply channel is closed. Either way, once.
     */
    private void awaitOutcome() {
        long bound = completionTimeoutMs();
        boolean interrupted = false;
        boolean finished;
        try {
            finished = done.await(bound, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
            finished = done.getCount() == 0;
        }
        if (!finished) {
            String cause = interrupted ? "its caller was interrupted" : "it did not complete within " + bound + " ms";
            if (state.compareAndSet(QUEUED, CANCELLED)) {
                closeWith("admin command '" + commandName() + "' was cancelled before its processor started it (" + cause
                        + "); it will not run");
                done.countDown();
            } else if (done.getCount() != 0) {
                closeWith("admin command '" + commandName() + "' started on its processor and had not completed (" + cause
                        + "); it may still complete, and nothing more from it will reach this caller");
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new com.telamin.mongoose.exception.AdminCommandException("Interrupted while publishing admin command");
        }
    }

    /** How long a caller waits: {@code mongoose.admin.completionTimeoutMs}, else {@value #DEFAULT_COMPLETION_TIMEOUT_MS}. */
    static long completionTimeoutMs() {
        return Long.getLong("mongoose.admin.completionTimeoutMs", DEFAULT_COMPLETION_TIMEOUT_MS);
    }

    /** Claim this command for execution: false when its caller cancelled it, or it was already claimed (a retry). */
    public boolean claim() {
        return state.compareAndSet(QUEUED, CLAIMED);
    }

    private String commandName() {
        return name != null ? name : args == null || args.isEmpty() ? "" : args.get(0);
    }

    /** A reply, while the caller still reads the channel; dropped after. */
    private synchronized boolean reply(Consumer<Object> to, Object message) {
        if (!open) return false;
        to.accept(message);
        return true;
    }

    /** The caller's last message, and the channel closed with it: nothing after it reaches the caller. */
    private synchronized void closeWith(String message) {
        if (!open) return;
        open = false;
        errOutput.accept(message);
    }

    /** Completed: the channel closes and the caller is released. Idempotent. */
    private void complete() {
        synchronized (this) {
            open = false;
        }
        done.countDown();
    }

    /**
     * Option A: deliver the command to {@code processor} as an ordinary event,
     * {@code Signal("admin:<command>", request)}, so it runs in an event cycle. The request carries the arguments and
     * the reply channel. No reply from any handler is answered with an error, so a caller is never left guessing
     * whether anything ran. A handler that throws is answered with the exception; note that a Fluxtion processor whose
     * node throws inside {@code onEvent} is left with its processing flag set, and stops processing (spec 3a finding 2).
     */
    public void executeAsSignal(com.telamin.fluxtion.runtime.DataFlow processor) {
        String command = args.get(0);
        AdminCommandRequest request = new AdminCommandRequest();
        request.setCommand(command);
        request.setArguments(List.copyOf(args.subList(1, args.size())));
        executed = true;
        boolean[] replied = {false};
        request.setOutput(o -> replied[0] |= reply(output, o));
        request.setErrOutput(o -> replied[0] |= reply(errOutput, o));
        RuntimeException failed = null;
        try {
            processor.onEvent(new com.telamin.fluxtion.runtime.event.Signal<>(
                    com.telamin.mongoose.service.admin.AdminCommandRegistry.SIGNAL_PREFIX + command, request));
            if (!replied[0]) {
                reply(errOutput, "admin command '" + command + "' was delivered to its processor, and no handler replied"
                        + " during the call (none is registered for it, or the processor is mid-cycle and queued it)");
            }
        } catch (RuntimeException e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            reply(errOutput, "problem executing command exception:" + e.getMessage() + "\n" + sw);
            failed = e;
        } finally {
            complete();
        }
        // #48 review, finding 1: the caller is answered and released above, once. The failure is then the DISPATCH's, as
        // any event's that throws: the agent reports it, the recorder marks the input Failed (D4), and its retry is a
        // no-op (the command is claimed). Swallowed here, it was recorded as a successful invocation and replayed.
        if (failed != null) throw failed;
    }

    /**
     * Answer the caller with {@code message} and release it, without running the command: its processor could not
     * run it. The invoker calls it for anything that fails before the command runs, so a caller is never left waiting.
     */
    public void refuse(String message) {
        try {
            reply(errOutput, message);
        } finally {
            complete();
        }
    }

    /** Whether this command has started to execute: a retried dispatch of it must not run it again. */
    public boolean executed() {
        return executed;
    }

    /**
     * Execute this command using current args and output consumers, handling and reporting exceptions. Marks the
     * command {@link #executed()} first, so a retry of the dispatch that ran it does not run it again.
     */
    public void executeCommand() {
        executed = true;
        try {
            commandWithOutput.processAdminCommand(args, o -> reply(output, o), o -> reply(errOutput, o));
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            reply(errOutput, "problem executing command exception:" + e.getMessage() + "\n" + sw);
        } finally {
            complete();
        }
    }
}
