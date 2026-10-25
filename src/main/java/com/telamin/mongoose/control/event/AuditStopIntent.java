/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/**
 * End the audit capture of the processor registered as {@code generation}: answered once the capture is uninstalled;
 * its resource is closed after, by the closer.
 *
 * @param bound the request names a registration; otherwise it addresses whichever registration answers to {@code name}
 */
public record AuditStopIntent(long requestId, long generation, String name, boolean bound) implements ControlInput {
}
