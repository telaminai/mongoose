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
 *
 * @param complete           everything stopped; the server is STOPPED
 * @param handedOff          the stop was called on one of the server's own threads and was handed to a stop thread;
 *                           its outcome is not known yet (use {@code awaitStopped})
 * @param blockedStartup     a synchronous startup step that did not return within the deadline, or null
 * @param groupsRunning      agent groups whose close did not finish within the deadline
 * @param servicesLeftRunning services not stopped, because a processor group may still use them or their stop did
 *                           not return in time
 */
public record StopResult(boolean complete, boolean handedOff, String blockedStartup, List<String> groupsRunning,
                         List<String> servicesLeftRunning) {

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
