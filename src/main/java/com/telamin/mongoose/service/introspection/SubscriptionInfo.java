/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.introspection;

/**
 * A single feed subscription held by a processor. Used by
 * {@link MongooseIntrospectionService} to describe per-processor dispatch
 * wiring.
 *
 * @param feed     event feed (source) name — matches the entry in
 *                 {@link com.telamin.mongoose.service.servercontrol.MongooseServerController#registeredServices()}
 *                 for the corresponding feed, when registered as a service
 * @param callback callback-type label (e.g. {@code "onEventCallBack"});
 *                 the simple-name form of the subscription's callback type
 */
public record SubscriptionInfo(String feed, String callback) {
}
