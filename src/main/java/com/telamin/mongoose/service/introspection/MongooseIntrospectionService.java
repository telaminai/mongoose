/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.introspection;

import java.util.Map;

/**
 * Read-only introspection surface over a running Mongoose server, intended
 * for admin / monitoring UIs. Separates "what is running and how is it wired
 * up?" from the lifecycle / mutation API in
 * {@link com.telamin.mongoose.service.servercontrol.MongooseServerController}.
 *
 * <p>The implementation is constructed and registered as a {@code Service}
 * by {@code MongooseServer} at startup, so consumers can inject it via
 * {@code @ServiceRegistered} the same way they pick up
 * {@code AdminCommandRegistry}:
 *
 * <pre>{@code
 * @ServiceRegistered
 * public void introspection(MongooseIntrospectionService svc, String name) {
 *     this.introspection = svc;
 * }
 * }</pre>
 *
 * Returned records are immutable snapshots — call again to refresh.
 */
public interface MongooseIntrospectionService {

    String SERVICE_NAME = "com.telamin.mongoose.service.introspection.MongooseIntrospectionService";

    /**
     * Per-agent-group snapshot: thread + idle-strategy configuration and the
     * processors hosted by the group (with their feed subscriptions).
     *
     * @return ordered map keyed by agent group name; never {@code null}
     */
    Map<String, AgentGroupSnapshot> agentGroups();

    /**
     * Per-feed snapshot: every event feed currently dispatching, the agent
     * groups consuming it, and the queue paths involved.
     *
     * @return ordered map keyed by feed (source) name; never {@code null}
     */
    Map<String, FeedTopology> feedTopology();
}
