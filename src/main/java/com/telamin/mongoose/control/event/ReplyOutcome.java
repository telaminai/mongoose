/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/** The one final answer every control request gets. */
public enum ReplyOutcome {
    APPLIED, REFUSED, FAILED, NOT_RUNNING
}
