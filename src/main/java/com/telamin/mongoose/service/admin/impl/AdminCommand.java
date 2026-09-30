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

    // ---- ONE invocation's lifetime. Every publish is its own AdminCommand (#48 correction, N2: a template reset and
    // re-queued itself, so a timed-out command still running completed its NEXT publish, and a cancelled queue slot was
    // revived). One atomic phase decides everything, and no lock is held while a reply is delivered (N3):
    //   QUEUED -> CLAIMED (its processor took it) -> COMPLETED (it finished, or was refused after the claim)
    //   QUEUED -> CANCELLED (its caller's wait ended first: it never runs)
    //   CLAIMED -> ABANDONED (its caller's wait ended while it ran: it may still complete; nothing more reaches the caller)
    static final int QUEUED = 0, CLAIMED = 1, CANCELLED = 2, COMPLETED = 3, ABANDONED = 4;
    private final java.util.concurrent.atomic.AtomicInteger phase = new java.util.concurrent.atomic.AtomicInteger(QUEUED);
    /** Counted down once, when the command completes, is refused or is cancelled: the caller's wait. Never reset. */
    private volatile java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    /**
     * A test's hold on the moment the caller's wait has ended, before it decides what that means; a no-op in the product.
     * It lets a test make the command complete exactly then (the race the phase settles).
     */
    static volatile java.util.function.Consumer<AdminCommand> beforeExpiry = c -> { };
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
    /** One invocation of {@code template} with {@code args} (the command name first), replying to the template's consumers. */
    AdminCommand(AdminCommand template, List<String> args) {
        this.commandWithOutput = template.commandWithOutput;
        this.targetQueue = template.targetQueue;
        this.output = template.output;
        this.errOutput = template.errOutput;
        this.args = new ArrayList<>(args);
        this.name = template.name;
        this.signalRouted = template.signalRouted;
    }

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
        AdminCommand invocation = new AdminCommand(this, adminCommandRequest);
        send(invocation, invocation.semaphore);                 // a request is its own invocation, admitted alone
    }

    /**
     * Publish the supplied argument list to the target queue or execute directly. Each call is its own invocation, with
     * its own arguments, claim, completion and reply lifetime; this template only admits one caller at a time ("busy").
     *
     * @param value the command arguments including the command name as first element
     */
    public void publishCommand(List<String> value) {
        AdminCommand invocation = new AdminCommand(this, value);
        send(invocation, semaphore);
    }

    /** Admit, publish and await {@code invocation}, once; {@code admission} is released when its caller returns. */
    private void send(AdminCommand invocation, Semaphore admission) {
        if (targetQueue == null) {
            commandWithOutput.processAdminCommand(invocation.args, invocation.output, invocation.errOutput);
            return;
        }
        boolean admitted;
        try {
            admitted = admission.tryAcquire(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new com.telamin.mongoose.exception.AdminCommandException("Interrupted while publishing admin command", e);
        }
        if (!admitted) {
            invocation.output.accept("command is busy try again");
            return;
        }
        try {
            targetQueue.publish(invocation);
            invocation.awaitOutcome();
        } finally {
            admission.release();                                // safe at a timeout: the running invocation is its own
        }
    }

    /**
     * Wait for this invocation's outcome, bounded (#48 review, finding 3). The bound's end is decided by the phase alone,
     * never by a reply consumer (#48 correction, N3: a consumer blocked in delivery held the monitor the timeout needed).
     * Unclaimed work is CANCELLED: it never runs later, and the caller is told. Claimed work is ABANDONED: it has started,
     * so it is not called cancelled; the caller is told it may still complete, and no reply that has not yet begun reaches
     * the caller. If it COMPLETED just as the wait ended, that is its outcome and nothing more is said. The caller's final
     * message is delivered on the caller's own thread, by its own error consumer.
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
            beforeExpiry.accept(this);
            expire(interrupted ? "its caller was interrupted" : "it did not complete within " + bound + " ms");
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
        return phase.compareAndSet(QUEUED, CLAIMED);
    }

    private String commandName() {
        return name != null ? name : args == null || args.isEmpty() ? "" : args.get(0);
    }

    /**
     * A reply, while the caller still reads the channel (QUEUED or CLAIMED); dropped after. No lock is held while the
     * consumer runs, so a consumer that blocks cannot hold the caller past its bound (#48 correction, N3). A delivery that
     * has already begun when the channel closes is not retracted: it may complete after the caller's final message.
     */
    private boolean reply(Consumer<Object> to, Object message) {
        int p = phase.get();
        if (p != QUEUED && p != CLAIMED) return false;
        to.accept(message);
        return true;
    }

    /** The caller's wait ended first: cancel unclaimed work, or abandon claimed work; either is said once. No lock. */
    private void expire(String cause) {
        if (phase.compareAndSet(QUEUED, CANCELLED)) {
            done.countDown();
            errOutput.accept("admin command '" + commandName() + "' was cancelled before its processor started it ("
                    + cause + "); it will not run");
        } else if (phase.compareAndSet(CLAIMED, ABANDONED)) {
            errOutput.accept("admin command '" + commandName() + "' started on its processor and had not completed ("
                    + cause + "); it may still complete: no new reply delivery will begin, and a delivery already in progress may still finish");
        }
    }

    /** Completed (or refused after its claim): the channel closes and the caller is released. Idempotent. */
    private void complete() {
        phase.compareAndSet(CLAIMED, COMPLETED);
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

    /**
     * This invocation's outcome for a recording: whether its command RAN (started to execute) in the dispatch that
     * delivered it. False when its caller cancelled it before it was claimed, or it was refused before it ran: then it is
     * not an input of the processor, and must not be recorded as an invocation (#48 correction, N1). A command that ran and
     * threw is recorded as the failed dispatch it is.
     */
    public boolean ran() {
        return executed;
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
