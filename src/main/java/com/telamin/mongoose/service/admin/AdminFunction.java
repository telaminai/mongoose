/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.admin;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

import java.util.List;
import java.util.function.Consumer;

/**
 * Represents a functional interface for handling administrative commands. This interface
 * allows commands to be processed using input arguments represented as a list of strings,
 * producing output data and error messages, which are supplied to respective consumers.
 * <p>
 * This interface is typically used for registering and invoking administrative commands
 * within a system using a pluggable or extensible framework.
 *
 * @param <OUT> the type of the output data produced by the command
 * @param <ERR> the type of the error data produced by the command
 */
@Experimental
public interface AdminFunction<OUT, ERR> {

    /**
     * Processes an administrative command using the given list of input commands,
     * and handles standard output and error output via the specified consumers.
     *
     * @param commands  the list of command strings representing the command
     *                  input to be processed
     * @param output    a consumer for handling the standard output produced by
     *                  the command processing, such as result messages or successful
     *                  execution notifications
     * @param errOutput a consumer for handling the error output produced by the command
     *                  processing, such as error messages or exception details
     */
    void processAdminCommand(List<String> commands, Consumer<OUT> output, Consumer<ERR> errOutput);
}
