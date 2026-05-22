/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.introspection;

import java.util.List;

/**
 * Snapshot of a single agent group at a point in time. Returned by
 * {@link MongooseIntrospectionService#agentGroups()} for admin / monitoring
 * UIs. Fields populated from the running {@code Thread} are {@code null}
 * before the runner has been started.
 *
 * @param group              agent group name
 * @param kind               {@code "processor"} for event-processor groups,
 *                           {@code "worker"} for worker-service groups
 * @param idleStrategyClass  fully qualified class name of the
 *                           {@code IdleStrategy} driving the runner
 * @param threadName         OS thread name once the runner is running,
 *                           else {@code null}
 * @param threadState        {@code Thread.State} as a string, else {@code null}
 * @param daemon             {@code true} when the running thread is a daemon
 *                           thread
 * @param priority           {@code Thread} priority (1–10), or {@code 0} when
 *                           the runner hasn't started
 * @param processors         per-processor snapshots; empty for worker-service
 *                           groups (workers aren't named processors)
 */
public record AgentGroupSnapshot(
        String group,
        String kind,
        String idleStrategyClass,
        String threadName,
        String threadState,
        boolean daemon,
        int priority,
        List<ProcessorInfo> processors) {
}
