/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/** Run an opaque task on the group's thread: the deprecated {@code runOnAgentThread}. Recorded as a marker only. */
public record RunIntent(long requestId) implements ControlInput {
}
