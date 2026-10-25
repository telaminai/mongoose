/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/**
 * Stop the processor registered as {@code generation}.
 *
 * @param bound    the stop was bound to a registration (always, except a legacy stop of a name nothing admitted)
 * @param addTaken the add of that registration had already been taken when this stop was applied
 */
public record StopIntent(long requestId, long generation, String name, boolean bound, boolean addTaken)
        implements ControlInput {
}
