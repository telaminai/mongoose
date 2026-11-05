/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/**
 * An input to a group's control processor. Commands carry the {@code requestId} the sender was given; completions and
 * effect results carry the {@code opId} of the effect they answer. Every input is an immutable, normalised intent: no
 * supplier, task, service object or resource ever enters the control processor.
 */
public interface ControlInput {
    long requestId();
}
