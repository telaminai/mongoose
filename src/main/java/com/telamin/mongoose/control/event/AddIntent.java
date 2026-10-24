/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/**
 * Build, wire and start a processor admitted under {@code name} as {@code generation}.
 *
 * @param incarnation per-name count of accepted adds in this group, deterministic given the order of adds of that name
 * @param cancelled   a stop consumed before this add arrived cancelled it (read on the agent thread when applied)
 * @param boot        accepted before the group started: a failed start fails the boot unless isolation is configured
 * @param failBootOnStartFailure the group's policy for a failed boot start: fail the boot, or isolate and report
 * @param legacy      added without send-time admission (the name is only known once the supplier runs)
 */
public record AddIntent(long requestId, long generation, int incarnation, String name, boolean cancelled,
                        boolean boot, boolean failBootOnStartFailure, boolean legacy) implements ControlInput {
}
