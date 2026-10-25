/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/**
 * Begin capturing the audit of the processor registered as {@code generation} (design "Audit capture"). The capture is
 * opened off the group's thread and installed on it once open; the request is answered when it records, or not.
 *
 * @param bound the request names a registration; otherwise it addresses whichever registration answers to {@code name}
 */
public record AuditStartIntent(long requestId, long generation, String name, boolean bound) implements ControlInput {
}
