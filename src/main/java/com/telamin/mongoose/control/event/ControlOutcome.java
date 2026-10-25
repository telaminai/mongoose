/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/** The outcome of one effect: {@code PENDING} is never final; {@code SUCCEEDED} and {@code FAILED} are. */
public enum ControlOutcome {
    PENDING, SUCCEEDED, FAILED
}
