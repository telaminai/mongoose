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
 *   <li>{@link #installCapture} and normal {@link #uninstallCapture} on the group's agent thread, between event
 *       cycles; exclusive post-death cleanup may uninstall on a shutdown helper after joining the ended agent;</li>
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
     * Restore the configured listener on a live processor. {@code dataFlow} is null when the processor has gone or
     * has been quarantined after an execution failure. In that case detach the resource without invoking any saved
     * processor reference; throw if safe offline detachment is unsupported so ownership remains reported. The
     * resource stops receiving records before this returns.
     */
    void uninstallCapture(Object resource, String processorName, DataFlow dataFlow);

    /**
     * Forget a registration released by a directly embedded group (without a server). This must not close resources:
     * their handles remain owned by the group until the ordinary uninstall/close effects finish.
     */
    default void processorReleased(AuditCaptureIdentity identity, DataFlow flow) { }

    /**
     * Fail open (issue #77, M3): a capture whose record writes fail never stops the processor, but must not fail silently.
     * The group calls this <b>before</b> installing the resource (the install's own listener-swap record is the
     * recording's first write); the implementation calls {@code observer} (with a description) once per recording, at
     * its first write failure or, if that failure came before the observer, when the observer attaches. It is called on
     * whatever thread wrote and must catch anything the observer throws. If this method throws, the group logs it,
     * marks the capture unobservable (audit-capture health) and installs the capture anyway.
     */
    default void observeWriteFailures(Object resource, java.util.function.Consumer<String> observer) { }

    /**
     * Whether {@link #observeWriteFailures} reports anything. The default is false (an implementation that cannot fail
     * a write, or one written before issue #77); the group records such a capture as unobservable in the control audit.
     * This stays a default method at the next minor: implementations opt in by overriding both.
     */
    default boolean observesWriteFailures() { return false; }

    /** Flush and close the resource. */
    void closeCapture(Object resource) throws Exception;
}
