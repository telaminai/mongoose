/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/**
 * Whether a control reply was made while its group's control audit was degraded (issue #77, M3). It describes the
 * audit's state when the reply was made, not the reply's own record: {@link #NOT_DEGRADED} does not prove that every
 * record of the request was written (a record written after the reply can still be lost; the exported evidence then
 * says incomplete and the oracle never passes it).
 */
public enum EvidenceStatus {
    /** The group's control audit had no unacknowledged write failure when the reply was made. */
    NOT_DEGRADED,
    /** The group's control audit had an unacknowledged write failure when the reply was made: evidence may be missing. */
    UNVERIFIED
}
