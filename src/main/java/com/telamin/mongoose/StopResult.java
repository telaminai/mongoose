/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose;

import java.util.List;

/**
 * What a server stop achieved (design "Bounded shutdown"). A stop never claims more than happened: when it runs out of
 * its deadline it names what is still running, leaves the services a running processor group may use running, and
 * the server stays STOPPING until a later stop completes.
 * A failed service stop is not automatically invoked again: its side effects are unknown. Such a failure remains
 * incomplete on later attempts, while other safely releasable resources can finish stopping.
 *
 * @param complete           everything stopped; the server is STOPPED
 * @param handedOff          the stop was called on one of the server's own threads and was handed to a stop thread;
 *                           its outcome is not known yet (use {@code awaitStopped})
 * @param blockedStartup     a startup, admission or shutdown-coordination step blocking this attempt, or null
 * @param groupsRunning      groups or cleanup tasks whose successful completion has not been confirmed
 * @param servicesLeftRunning services not confirmed stopped, because a group may still use them, their stop is still
 *                           running, or their stop failed
 * @param heldNames          processor names still reserved by a dead group or a quarantined processor (issue #77, M4):
 *                           typed, so an operator need not parse {@code groupsRunning}
 */
public record StopResult(boolean complete, boolean handedOff, String blockedStartup, List<String> groupsRunning,
                         List<String> servicesLeftRunning, List<HeldName> heldNames) {

    /**
     * A processor name the server still holds after a stop because its owner cannot release it.
     *
     * @param reason why it is held: {@code "group dead"} or the quarantine failure
     */
    public record HeldName(String group, String name, long generation, String reason) { }

    public StopResult {
        groupsRunning = groupsRunning == null ? List.of() : List.copyOf(groupsRunning);
        servicesLeftRunning = servicesLeftRunning == null ? List.of() : List.copyOf(servicesLeftRunning);
        heldNames = heldNames == null ? List.of() : List.copyOf(heldNames);
    }

    /** The pre-#77 shape: no held names. */
    public StopResult(boolean complete, boolean handedOff, String blockedStartup, List<String> groupsRunning,
                      List<String> servicesLeftRunning) {
        this(complete, handedOff, blockedStartup, groupsRunning, servicesLeftRunning, List.of());
    }

    /** This result with {@code names} held. */
    public StopResult withHeldNames(List<HeldName> names) {
        return new StopResult(complete, handedOff, blockedStartup, groupsRunning, servicesLeftRunning, names);
    }

    /**
     * The stop's classification in the shared failure taxonomy (issue #77): {@code NONE} when complete,
     * {@code DETACHED} when handed to a stop thread (the caller is detached from an outcome not yet known), otherwise
     * {@code FAILED_SHUTDOWN} (the named work did not confirm completion within the deadline).
     */
    public com.telamin.mongoose.control.event.FailureKind failureKind() {
        if (complete) return com.telamin.mongoose.control.event.FailureKind.NONE;
        return handedOff ? com.telamin.mongoose.control.event.FailureKind.DETACHED
                : com.telamin.mongoose.control.event.FailureKind.FAILED_SHUTDOWN;
    }

    public static StopResult completed() {
        return new StopResult(true, false, null, List.of(), List.of());
    }

    public static StopResult handedOffToStopThread() {
        return new StopResult(false, true, null, List.of(), List.of());
    }

    public static StopResult incomplete(String blockedStartup, List<String> groupsRunning, List<String> servicesLeftRunning) {
        return new StopResult(false, false, blockedStartup, List.copyOf(groupsRunning), List.copyOf(servicesLeftRunning));
    }
}
