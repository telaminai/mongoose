/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

/**
 * A never-drop queue latched (telaminai/mongoose#79, decision D5): what the overload kill switch is given.
 *
 * @param feed        the feed
 * @param queue       the subscriber queue binding name
 * @param group       the subscriber group, or null
 * @param kind        {@code "refused"} or {@code "dropped"}
 * @param sequence    the feed sequence number of the lost item, or -1 for a refusal (a refused item is never numbered)
 * @param reason      why
 * @param epochMillis when
 */
public record OverloadLoss(String feed, String queue, String group, String kind, long sequence, String reason,
                           long epochMillis) {
}
