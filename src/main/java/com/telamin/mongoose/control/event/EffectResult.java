/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/**
 * What an effect actually did, fed back as its own audited event cycle. {@code requestId} and {@code generation} are
 * those of the effect it answers, so the result links back to the command that caused it. {@code kind} classifies a
 * failure (and, for a {@code Reply} effect, carries the reply's own kind) so rules propagate it without reading text.
 */
public record EffectResult(String opId, long requestId, String effect, ControlOutcome outcome, long generation,
                           String name, String reason, FailureKind kind) implements ControlInput {

    public EffectResult {
        if (kind == null) kind = FailureKind.defaultFor(outcome);
    }

    /** A result whose kind is its outcome's default: {@code FAILED_EFFECT} when failed, otherwise {@code NONE}. */
    public EffectResult(String opId, long requestId, String effect, ControlOutcome outcome, long generation,
                        String name, String reason) {
        this(opId, requestId, effect, outcome, generation, name, reason, FailureKind.defaultFor(outcome));
    }

    public boolean succeeded() {
        return outcome == ControlOutcome.SUCCEEDED;
    }
}
