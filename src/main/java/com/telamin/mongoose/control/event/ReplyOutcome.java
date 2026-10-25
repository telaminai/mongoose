/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/** The one final answer every control request gets. */
public enum ReplyOutcome {
    /** The change is complete, not just accepted. A stop of a processor that has already gone is also applied. */
    APPLIED,
    /** Nothing changed: the request was turned down before any effect ran (a name in use, a full mailbox, ...). */
    REFUSED,
    /** The request was accepted and acted on, but its effect did not complete (a start that threw, an open that timed out). */
    FAILED,
    /** The group, or the server, is closing, closed or dead: the request was not applied. */
    NOT_RUNNING
}
