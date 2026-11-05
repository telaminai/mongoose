/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

/**
 * Called when a never-drop queue of a feed with {@code killSwitch: true} latches (telaminai/mongoose#79, decision D5):
 * the first refusal or drop since the queue was last cleared. Off by default: it fires only for feeds that enable it,
 * and only once a hook is set with {@code MongooseServer.setOverloadKillSwitch}.
 * <p>
 * It runs on a dedicated daemon thread ({@code mongoose-overload-kill-switch}), never on the publishing thread and
 * never under a publish lock, so a hook may block, stop the server or publish. A hook that throws is logged.
 */
@FunctionalInterface
public interface OverloadKillSwitch {
    void onNeverDropLoss(OverloadLoss loss);
}
