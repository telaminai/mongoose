/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.admin.impl;

import com.fluxtion.runtime.annotations.feature.Experimental;
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
        this.args.add(0, adminCommandRequest.getCommand());
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
        } else {
            try {
                if (semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
                    args = value;
                    targetQueue.publish(this);
                    semaphore.acquire();
                    semaphore.release();
                } else {
                    output.accept("command is busy try again");
                }
            } catch (InterruptedException e) {
                throw new com.telamin.mongoose.exception.AdminCommandException("Interrupted while publishing admin command", e);
            }
        }
    }

    /**
     * Execute this command using current args and output consumers, handling and reporting exceptions.
     */
    public void executeCommand() {
        try {
            commandWithOutput.processAdminCommand(args, output, errOutput);
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            errOutput.accept("problem executing command exception:" + e.getMessage() + "\n" + sw);
        } finally {
            semaphore.release();
        }
    }
}
