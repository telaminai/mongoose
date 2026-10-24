/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.effect;

/**
 * An effect a control rule requests. Requesting is not performing: effects are performed at the control processor's
 * {@code batchEnd()}, after the decision that asked for them has been recorded, and each result is fed back as an
 * {@link com.telamin.mongoose.control.event.EffectResult}.
 */
public sealed interface ControlEffect permits ControlEffect.BuildAndStart, ControlEffect.Release,
        ControlEffect.ReleaseName, ControlEffect.CancelRegistration, ControlEffect.DeliverService, ControlEffect.RunTask,
        ControlEffect.Reply, ControlEffect.FailBoot, ControlEffect.ReportFailure {

    String opId();

    long requestId();

    long generation();

    String name();

    /** The effect's name in the audit vocabulary. */
    default String effect() {
        return getClass().getSimpleName();
    }

    record BuildAndStart(String opId, long requestId, long generation, String name) implements ControlEffect { }

    record Release(String opId, long requestId, long generation, String name, boolean stopping) implements ControlEffect { }

    record ReleaseName(String opId, long requestId, long generation, String name) implements ControlEffect { }

    record CancelRegistration(String opId, long requestId, long generation, String name) implements ControlEffect { }

    record DeliverService(String opId, long requestId, long generation, String name, String serviceName,
                          boolean register) implements ControlEffect { }

    record RunTask(String opId, long requestId, long generation, String name) implements ControlEffect { }

    /** A boot processor failed to start under the fail-fast policy: the group's start fails once its boot drain ends. */
    record FailBoot(String opId, long requestId, long generation, String name, String reason) implements ControlEffect { }

    /** A failure the group reports through {@code ErrorReporting} and carries on. */
    record ReportFailure(String opId, long requestId, long generation, String name, String what) implements ControlEffect { }

    record Reply(String opId, long requestId, long generation, String name,
                 com.telamin.mongoose.control.event.ReplyOutcome outcome, String reason) implements ControlEffect { }
}
