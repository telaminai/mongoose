/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/**
 * What an effect actually did, fed back as its own audited event cycle. {@code requestId} and {@code generation} are
 * those of the effect it answers, so the result links back to the command that caused it.
 */
public record EffectResult(String opId, long requestId, String effect, ControlOutcome outcome, long generation,
                           String name, String reason) implements ControlInput {

    public boolean succeeded() {
        return outcome == ControlOutcome.SUCCEEDED;
    }
}
