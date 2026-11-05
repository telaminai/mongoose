/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/** The group is closing: stop every started processor in add order. */
public record CloseIntent(long requestId) implements ControlInput {
}
