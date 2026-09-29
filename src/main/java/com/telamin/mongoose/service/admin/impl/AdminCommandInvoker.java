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
     * A lambda command, bracketed as a generated processor brackets an event (its {@code auditEvent} and
     * {@code afterEvent}): the processor's clock and audit logger are told an {@link AdminCommandEvent} was received,
     * the lambda runs, and both are told processing is complete. So the command has its own audit record, its
     * {@code auditLog} writes land in it, and it carries the command's own instant. Both auditors are found by name
     * ({@code getAuditorById}); a processor without them (a hand-written one) runs the lambda as before.
     *
     * <p>Not a full event cycle: nothing the lambda changes is marked dirty, so nothing downstream reacts, and the
     * processor's {@code processing} flag is not set, so an event the lambda raises is dispatched at once rather than
     * queued. A command that must propagate is signal-routed ({@code registerSignalCommand}).
     */
    private static void executeInAuditRecord(AdminCommand adminCommand, DataFlow processor) {
        List<String> args = adminCommand.getArgs();
        Object event = new com.telamin.mongoose.service.admin.AdminCommandEvent(
                args.isEmpty() ? "" : args.get(0), args.size() > 1 ? List.copyOf(args.subList(1, args.size())) : List.of());
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
