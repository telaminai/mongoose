/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 */

package com.telamin.mongoose.service.admin.impl;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.mongoose.dispatch.AbstractEventToInvocationStrategy;

import java.util.List;

/**
 * Invocation strategy that executes AdminCommand events by calling executeCommand on receipt.
 */
@Experimental
public class AdminCommandInvoker extends AbstractEventToInvocationStrategy {

    /**
     * Create a new AdminCommandInvoker.
     */
    public AdminCommandInvoker() {
        super();
    }

    @Override
    protected void dispatchEvent(Object event, DataFlow eventProcessor) {
        AdminCommand adminCommand = (AdminCommand) event;
        if (adminCommand.executed()) {
            // a retry of a dispatch that already ran this command (its cycle failed afterwards, e.g. an event it
            // raised threw while draining): the failure is already reported; the command's effects happen once
            return;
        }
        if (adminCommand.isSignalRouted()) {
            adminCommand.executeAsSignal(eventProcessor);           // option A: in the processor's event cycle
        } else {
            executeInAuditRecord(adminCommand, eventProcessor);     // a lambda: bracketed by the processor's audit record
        }
    }

    /**
     * A lambda command, run as its own event cycle through {@link DataFlow#runInEventCycle} when the processor
     * implements it. Otherwise (a processor generated before fluxtion runtime 1.1.0) it is bracketed as a generated
     * processor brackets an event (its {@code auditEvent} and
     * {@code afterEvent}): the processor's clock and audit logger are told an {@link AdminCommandEvent} was received,
     * the lambda runs, and both are told processing is complete. So the command has its own audit record, its
     * {@code auditLog} writes land in it, and it carries the command's own instant. Both auditors are found by name
     * ({@code getAuditorById}); a processor without them (a hand-written one) runs the lambda as before.
     *
     * <p>The bracket is not a full event cycle: nothing the lambda changes is marked dirty, so nothing downstream reacts, and the
     * processor's {@code processing} flag is not set, so an event the lambda raises is dispatched at once rather than
     * queued. A command that must propagate is signal-routed ({@code registerSignalCommand}).
     */
    private static void executeInAuditRecord(AdminCommand adminCommand, DataFlow processor) {
        List<String> args = adminCommand.getArgs();
        Object event = new com.telamin.mongoose.service.admin.AdminCommandEvent(
                args.isEmpty() ? "" : args.get(0), args.size() > 1 ? List.copyOf(args.subList(1, args.size())) : List.of());
        // the proper form: the processor runs the command as its own event cycle (DataFlow.runInEventCycle, fluxtion
        // runtime 1.1.0), so an event the command raises is queued and dispatched after it. A processor generated
        // before that has no implementation, and the interface default refuses BEFORE running the action; only
        // then is the command bracketed instead. Any other refusal answers the caller; a command runs at most once
        // (dispatchEvent). AdminCommandFailureTest holds both.
        if (!NO_CYCLE.containsKey(processor.getClass())) {
            try {
                processor.runInEventCycle(event, adminCommand::executeCommand);
                return;
            } catch (UnsupportedOperationException refused) {
                if (adminCommand.executed()) {
                    throw refused;                                  // the cycle failed after the command ran
                }
                if (!refusedByTheDefault(refused)) {
                    adminCommand.refuse(cannotRun(adminCommand, refused));
                    return;
                }
                NO_CYCLE.put(processor.getClass(), Boolean.TRUE);   // a processor that predates it: bracket from now on
            } catch (RuntimeException failed) {
                if (adminCommand.executed()) {
                    // the command ran and answered; closing its cycle failed (an event it raised threw). The agent
                    // reports and retries the dispatch, and the retry is a no-op (dispatchEvent)
                    throw failed;
                }
                // refused before the command ran, e.g. IllegalStateException from a processor left mid-cycle by a
                // node that threw: answer the caller rather than leave it waiting; there is nothing to retry
                adminCommand.refuse(cannotRun(adminCommand, failed));
                return;
            }
        }
        // for a processor without runInEventCycle: the processor's own audit calls, found by name (spec 3d: unsafe for
        // a command that redispatches, which dispatches at once inside the open record)
        com.telamin.fluxtion.runtime.time.Clock clock = auditor(processor, "clock");
        com.telamin.fluxtion.runtime.audit.EventLogManager log =
                auditor(processor, com.telamin.fluxtion.runtime.audit.EventLogManager.NODE_NAME);
        if (clock != null) clock.eventReceived(event);
        if (log != null) log.eventReceived(event);
        try {
            adminCommand.executeCommand();
        } finally {
            if (clock != null) clock.processingComplete();
            if (log != null) log.processingComplete();
        }
    }

    /** The interface default's own refusal (thrown by DataFlow.runInEventCycle itself), as opposed to one from inside. */
    private static boolean refusedByTheDefault(UnsupportedOperationException refused) {
        StackTraceElement[] trace = refused.getStackTrace();
        return trace.length > 0 && DataFlow.class.getName().equals(trace[0].getClassName())
                && "runInEventCycle".equals(trace[0].getMethodName());
    }

    private static String cannotRun(AdminCommand adminCommand, RuntimeException why) {
        List<String> args = adminCommand.getArgs();
        return "admin command '" + (args.isEmpty() ? "" : args.get(0)) + "' did not run: its processor could not run it"
                + " in an event cycle: " + why;
    }

    /** Processor classes whose runInEventCycle refused (they predate it), so the refusal is paid once per class. */
    private static final java.util.Map<Class<?>, Boolean> NO_CYCLE = new java.util.concurrent.ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private static <A> A auditor(DataFlow processor, String name) {
        try {
            return (A) processor.getAuditorById(name);
        } catch (NoSuchFieldException | IllegalAccessException | ClassCastException e) {
            return null;
        }
    }

    @Override
    protected boolean isValidTarget(DataFlow eventProcessor) {
        return true;
    }
}
