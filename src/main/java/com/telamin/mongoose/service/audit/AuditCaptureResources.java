/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.audit;

import com.telamin.fluxtion.runtime.DataFlow;

/**
 * The physical side of audit capture, driven by a processor group's control processor (design "Audit capture"). The
 * group decides; this only does. Each method is called on a known thread:
 * <ul>
 *   <li>{@link #openCapture} on an I/O helper: file work never lands on a processor's thread;</li>
 *   <li>{@link #installCapture} and {@link #uninstallCapture} on the group's agent thread, between event cycles;</li>
 *   <li>{@link #closeCapture} on the closer, exactly once per resource.</li>
 * </ul>
 * A resource is opaque to the group: it holds it by id in its resource table and hands it back.
 */
public interface AuditCaptureResources {

    /** Open a capture for {@code processorName} and return the resource; closes what it created before throwing. */
    Object openCapture(String processorName) throws Exception;

    /** Registration-scoped open. Existing resource implementations remain source compatible. */
    default Object openCaptureScoped(AuditCaptureIdentity identity) throws Exception {
        return openCapture(identity.name());
    }

    /** Install an open capture on the processor, in front of the listener the server configured on it. */
    void installCapture(Object resource, String processorName, DataFlow dataFlow);

    /**
     * Restore the configured listener on the processor; {@code dataFlow} is null once the processor has gone. The
     * resource stops receiving records before this returns.
     */
    void uninstallCapture(Object resource, String processorName, DataFlow dataFlow);

    /** Flush and close the resource. */
    void closeCapture(Object resource) throws Exception;
}
