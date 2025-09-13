/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.admin;

import com.fluxtion.runtime.annotations.feature.Experimental;

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
}
