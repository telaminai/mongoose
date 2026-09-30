/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.admin;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

import java.util.List;

/**
 * AdminCommandRegistry provides an interface to manage and process administrative commands
 * in a controlled and extensible manner. It allows registering new commands, processing
 * incoming command requests, and retrieving a list of available commands.
 * <p>
 * This interface is marked as experimental and subject to change in future releases.
 */
@Experimental
public interface AdminCommandRegistry {

    /**
     * Registers a new administrative command within the system.
     *
     * @param <OUT>   the type of the output data produced by the command
     * @param <ERR>   the type of the error data produced by the command
     * @param name    the name of the command to register
     * @param command the implementation of the command logic as an {@link AdminFunction}
     */
    <OUT, ERR> void registerCommand(String name, AdminFunction<OUT, ERR> command);

    /**
     * Processes an incoming administrative command request. The method takes an
     * {@link AdminCommandRequest} instance containing the command name, arguments,
     * and output handlers, and executes the appropriate registered command.
     *
     * @param command the {@link AdminCommandRequest} containing details of the
     *                command to be executed, including the name of the command,
     *                arguments, and output/error handlers
     */
    void processAdminCommandRequest(AdminCommandRequest command);

    /**
     * Retrieves a list of all currently registered administrative commands.
     *
     * @return a list of command names representing all available administrative commands
     */
    List<String> commandList();

    /** The filter string prefix of a signal-routed command's {@code Signal}: {@code admin:<command>}. */
    String SIGNAL_PREFIX = "admin:";

    /**
     * Register a command that runs IN the registering processor's event cycle (option A of
     * design-doc admin-commands-in-an-event-cycle): when invoked, the processor receives
     * {@code onEvent(new Signal<>("admin:" + name, request))}, an ordinary event, so the command is audited, a node's
     * {@code auditLog} writes land in its record, and state it changes propagates as any event's does. A node handles it
     * with a filtered signal handler, {@code @OnEventHandler(filterString = "admin:<name>")} taking a
     * {@code Signal<AdminCommandRequest>}, and replies through {@code request.getOutput()}. A command no handler replies
     * to is answered with an error. Must be called by a processor (from {@code @ServiceRegistered}): the command belongs
     * to it. Admin commands stay a Mongoose concept: only the generic {@code Signal} event is Fluxtion's.
     */
    default void registerSignalCommand(String name) {
        throw new UnsupportedOperationException(getClass().getName() + " does not support signal-routed commands");
    }
}
