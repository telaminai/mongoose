/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 */

package com.telamin.mongoose.service.admin.impl;

import com.telamin.fluxtion.runtime.DataFlow;

import java.util.List;
import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.mongoose.dispatch.AbstractEventToInvocationStrategy;

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
        // then is the command bracketed instead. A command that itself throws is never run twice.
        if (!NO_CYCLE.containsKey(processor.getClass())) {
            boolean[] ran = {false};
            try {
                processor.runInEventCycle(event, () -> {
                    ran[0] = true;
                    adminCommand.executeCommand();
                });
                return;
            } catch (UnsupportedOperationException refused) {
                if (ran[0]) {
                    throw refused;                                  // the command's own exception
                }
                NO_CYCLE.put(processor.getClass(), Boolean.TRUE);   // a processor that predates it: bracket from now on
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
