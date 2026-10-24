/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

/**
 * A processor failed to start while the server was booting, and boot isolation is off (the default; see
 * {@code MongooseServerConfig.isolateProcessorStartFailuresAtBoot}). Thrown out of the group agent's {@code onStart},
 * so the group does not become active and the failure reaches the agent's error handler; the processor itself has
 * already been dropped from the group and its resources released.
 */
public class ProcessorStartException extends RuntimeException {

    private final String groupName;
    private final String processorName;

    public ProcessorStartException(String groupName, String processorName, Throwable cause) {
        super("server boot failed: processor '" + processorName + "' in group '" + groupName + "' failed to start: "
                + cause, cause);
        this.groupName = groupName;
        this.processorName = processorName;
    }

    public String groupName() {
        return groupName;
    }

    public String processorName() {
        return processorName;
    }
}
