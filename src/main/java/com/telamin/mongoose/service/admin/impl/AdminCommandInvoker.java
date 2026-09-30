/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 */

package com.telamin.mongoose.service.admin.impl;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.mongoose.dispatch.AbstractEventToInvocationStrategy;

import com.telamin.mongoose.service.error.ErrorEvent;
import com.telamin.mongoose.service.error.ErrorReporting;

import java.util.List;
import java.util.logging.Logger;

/**
 * Invocation strategy that executes AdminCommand events by calling executeCommand on receipt.
 */
@Experimental
public class AdminCommandInvoker extends AbstractEventToInvocationStrategy {

    private static final Logger log = Logger.getLogger(AdminCommandInvoker.class.getName());

    /**
     * Create a new AdminCommandInvoker.
     */
    public AdminCommandInvoker() {
        super();
    }

    /**
     * A live command for a processor muted for a replay is refused, by name, now: the base strategy skips a muted target
     * silently, which left the caller waiting for a command nothing would ever run (#48 review, finding 3).
     */
    @Override
    public void processEvent(Object event) {
        AdminCommand adminCommand = (AdminCommand) event;
        for (int i = 0, n = eventProcessorSinks.size(); i < n; i++) {
            DataFlow target = eventProcessorSinks.get(i);
            if (mutedForReplay(target)) {
                if (adminCommand.claim()) {
                    adminCommand.refuse("admin command '" + name(adminCommand) + "' was refused: its processor is replaying,"
                            + " and a replayed processor receives no live input");
                }
                continue;
            }
            com.telamin.mongoose.dispatch.ProcessorContext.setCurrentProcessor(target);
            try {
                dispatchEvent(event, target);
            } finally {
                com.telamin.mongoose.dispatch.ProcessorContext.removeCurrentProcessor();
            }
        }
    }

    @Override
    protected void dispatchEvent(Object event, DataFlow eventProcessor) {
        AdminCommand adminCommand = (AdminCommand) event;
        if (!adminCommand.claim()) {
            // cancelled by its caller before it was claimed (it never runs later: #48 review, finding 3), or a retry of a
            // dispatch that already claimed it (its cycle failed afterwards): the command's effects happen at most once
            return;
        }
        try {
            if (adminCommand.isSignalRouted()) {
                adminCommand.executeAsSignal(eventProcessor);       // option A: in the processor's event cycle
            } else {
                executeInAuditRecord(adminCommand, eventProcessor); // a lambda: its own cycle, or the audit bracket
            }
        } catch (RuntimeException failed) {
            if (adminCommand.executed()) {
                // the command ran and answered; what failed came after it (e.g. an event it raised threw while its
                // cycle drained). The agent reports and retries the dispatch, and the retry is a no-op (above)
                throw failed;
            }
            // it failed BEFORE the command ran, wherever that was (a processor left mid-cycle refusing the cycle, the
            // arguments, the audit bracket): the caller is answered and released - never left waiting - and the
            // operator is told, since a processor that cannot run commands is what they must see
            String why = cannotRun(adminCommand, failed);
            log.warning(why);
            ErrorReporting.report("AdminCommandInvoker", why, failed, ErrorEvent.Severity.WARNING);
            adminCommand.refuse(why);                               // last: the caller is released once it is recorded
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
        // runtime 1.1.0), so an event the command raises is queued and dispatched after it. A processor that only
        // inherits the refusing default (generated before 1.1.0), or whose override refuses, is bracketed instead.
        // Anything that fails before the command runs answers the caller, and a command runs at most once
        // (dispatchEvent). AdminCommandFailureTest holds both.
        if (implementsTheCycle(processor.getClass())) {
            try {
                processor.runInEventCycle(event, adminCommand::executeCommand);
                return;
            } catch (UnsupportedOperationException refused) {
                if (adminCommand.executed()) {
                    throw refused;                                  // the cycle failed after the command ran
                }
                // an override that refuses (a generated processor may disable the path): this command is bracketed.
                // Anything else thrown before the command ran reaches dispatchEvent, which answers the caller
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

    /**
     * Whether {@code type} implements runInEventCycle, rather than inheriting the interface default that refuses (a
     * processor generated before fluxtion runtime 1.1.0): decided once per class, from the method itself.
     */
    private static boolean implementsTheCycle(Class<?> type) {
        return IMPLEMENTS_THE_CYCLE.computeIfAbsent(type, c -> {
            try {
                return !c.getMethod("runInEventCycle", Object.class, Runnable.class).isDefault();
            } catch (NoSuchMethodException predatesTheRuntime) {
                return false;
            }
        });
    }

    private static String name(AdminCommand adminCommand) {
        List<String> args = adminCommand.getArgs();
        return args == null || args.isEmpty() ? "" : args.get(0);
    }

    private static String cannotRun(AdminCommand adminCommand, RuntimeException why) {
        List<String> args = adminCommand.getArgs();
        return "admin command '" + (args.isEmpty() ? "" : args.get(0)) + "' did not run: its processor could not run it"
                + " in an event cycle: " + why;
    }

    /** Per processor class: whether it implements runInEventCycle (see {@link #implementsTheCycle}). */
    private static final java.util.Map<Class<?>, Boolean> IMPLEMENTS_THE_CYCLE = new java.util.concurrent.ConcurrentHashMap<>();

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
