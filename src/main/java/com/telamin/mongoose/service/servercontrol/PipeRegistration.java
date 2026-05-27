/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.servercontrol;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

/**
 * Server-side record of one configured pipe.
 *
 * <p>Recorded by {@code ServerConfigurator} when a {@code HandlerPipeConfig}
 * is processed at boot. Surfaced to admin / observability surfaces via
 * {@link MongooseServerController#registeredPipes()}.
 *
 * <p>Why a dedicated record rather than relying on the two underlying
 * service registrations: a pipe is a single logical concept in the
 * config domain. The two halves (NamedFeed + MessageSink) exist as
 * separate {@code Service<?>} entries in the global registry because
 * Mongoose's name-keyed registry can't carry two registrations with
 * the same name. This record carries the logical pairing so admin
 * UIs can render pipes as one entity with both halves visible,
 * instead of guessing at the {@code .sink} suffix convention.
 *
 * @param name        the feed-side service name (also the logical pipe name)
 * @param sinkName    the sink-side service name (default {@code name + ".sink"})
 * @param agentName   agent group hosting the pipe's feed side; null if non-agent
 * @param broadcast   whether the feed broadcasts to all subscribers
 * @param cacheEventLog whether the source caches events before startComplete
 */
@Experimental
public record PipeRegistration(
        String name,
        String sinkName,
        String agentName,
        boolean broadcast,
        boolean cacheEventLog
) {
}
