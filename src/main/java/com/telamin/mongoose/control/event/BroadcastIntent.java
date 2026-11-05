/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/** Deliver ({@code register}) or withdraw a service from every running processor in the group. */
public record BroadcastIntent(long requestId, String serviceName, boolean register, long version) implements ControlInput {
}
