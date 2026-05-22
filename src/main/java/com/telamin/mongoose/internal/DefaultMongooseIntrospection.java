/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.introspection.AgentGroupSnapshot;
import com.telamin.mongoose.service.introspection.FeedConsumer;
import com.telamin.mongoose.service.introspection.FeedTopology;
import com.telamin.mongoose.service.introspection.MongooseIntrospectionService;
import com.telamin.mongoose.service.introspection.ProcessorInfo;
import com.telamin.mongoose.service.introspection.SubscriptionInfo;
import org.agrona.concurrent.IdleStrategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link MongooseIntrospectionService} implementation. Wraps live
 * references to the server's composing-agent maps and the flow manager;
 * each call produces a fresh, immutable snapshot.
 * <p>
 * The maps are passed by reference (not copied) so the snapshot always
 * reflects the latest registration state without coupling to lifecycle
 * events.
 */
public final class DefaultMongooseIntrospection implements MongooseIntrospectionService {

    private final Map<String, ComposingEventProcessorAgentRunner> processorAgents;
    private final Map<String, ComposingWorkerServiceAgentRunner>  serviceAgents;
    private final EventFlowManager flowManager;

    public DefaultMongooseIntrospection(
            Map<String, ComposingEventProcessorAgentRunner> processorAgents,
            Map<String, ComposingWorkerServiceAgentRunner>  serviceAgents,
            EventFlowManager flowManager) {
        this.processorAgents = processorAgents;
        this.serviceAgents   = serviceAgents;
        this.flowManager     = flowManager;
    }

    @Override
    public Map<String, AgentGroupSnapshot> agentGroups() {
        Map<String, AgentGroupSnapshot> out = new LinkedHashMap<>();
        processorAgents.forEach((group, runner) -> {
            Thread t = runner.groupRunner().thread();
            IdleStrategy is = runner.idleStrategy();
            // Per-processor subscription map for this group — converts
            // EventSubscriptionKey to typed SubscriptionInfo so callers
            // never see internal key types.
            Map<String, List<EventSubscriptionKey<?>>> subs =
                    runner.group().subscriptionsByProcessorName();
            List<ProcessorInfo> processors = new ArrayList<>();
            for (NamedEventProcessor np : runner.group().registeredEventProcessors()) {
                String cls = np.eventProcessor() != null
                        ? np.eventProcessor().getClass().getName() : null;
                List<EventSubscriptionKey<?>> keys = subs.getOrDefault(np.name(), List.of());
                List<SubscriptionInfo> subInfos = new ArrayList<>(keys.size());
                for (EventSubscriptionKey<?> k : keys) {
                    subInfos.add(toSubscriptionInfo(k));
                }
                processors.add(new ProcessorInfo(np.name(), cls, subInfos));
            }
            out.put(group, new AgentGroupSnapshot(
                    group,
                    "processor",
                    is != null ? is.getClass().getName() : null,
                    t != null ? t.getName() : null,
                    t != null ? t.getState().name() : null,
                    t != null && t.isDaemon(),
                    t != null ? t.getPriority() : 0,
                    processors));
        });
        serviceAgents.forEach((group, runner) -> {
            // First-writer wins: a processor group of the same name takes
            // precedence over a worker group. In practice groups are
            // single-kind so this collision is theoretical.
            if (out.containsKey(group)) return;
            Thread t = runner.groupRunner().thread();
            IdleStrategy is = runner.idleStrategy();
            out.put(group, new AgentGroupSnapshot(
                    group,
                    "worker",
                    is != null ? is.getClass().getName() : null,
                    t != null ? t.getName() : null,
                    t != null ? t.getState().name() : null,
                    t != null && t.isDaemon(),
                    t != null ? t.getPriority() : 0,
                    List.of()));
        });
        return out;
    }

    @Override
    public Map<String, FeedTopology> feedTopology() {
        // Parse EventFlowManager.appendQueueInformation() once and convert to
        // typed records. We could thread the dispatcher's typed view through
        // instead — the parser is here to avoid an additional accessor on
        // EventFlowManager.
        Map<String, List<FeedConsumer>> consumersByFeed = new LinkedHashMap<>();
        if (flowManager != null) {
            StringBuilder sb = new StringBuilder();
            try {
                flowManager.appendQueueInformation(sb);
            } catch (Exception ignore) {
                return Map.of();
            }
            // Per-processor subscription map (group → processor → keys), used
            // to attribute each queue consumer to specific processors when
            // available. Group-fanout is the fallback otherwise.
            Map<String, Map<String, List<EventSubscriptionKey<?>>>> subsByGroup = new LinkedHashMap<>();
            processorAgents.forEach((group, runner) ->
                    subsByGroup.put(group, runner.group().subscriptionsByProcessorName()));

            String currentFeed = null;
            for (String raw : sb.toString().split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.equals("readQueues:")) continue;
                if (line.startsWith("eventSource:")) {
                    currentFeed = line.substring("eventSource:".length()).trim();
                    consumersByFeed.computeIfAbsent(currentFeed, k -> new ArrayList<>());
                } else if (currentFeed != null && line.contains("->")) {
                    String path = line.substring(0, line.indexOf("->")).trim();
                    String[] parts = path.split("/");
                    String group    = parts[0].isEmpty() ? path : parts[0];
                    String callback = parts[parts.length - 1];
                    if (callback.contains(".")) {
                        callback = callback.substring(callback.lastIndexOf('.') + 1);
                    }
                    consumersByFeed.get(currentFeed).add(new FeedConsumer(
                            group, callback, path, resolveProcessorsForFeed(group, currentFeed, subsByGroup)));
                }
            }
        }
        Map<String, FeedTopology> out = new LinkedHashMap<>();
        consumersByFeed.forEach((feed, cons) -> out.put(feed, new FeedTopology(feed, cons)));
        return out;
    }

    /**
     * Resolve which processors in {@code group} are subscribed to {@code feed}.
     * Prefers the per-processor subscription map (precise); falls back to the
     * group's full membership if the feed isn't named in any processor's keys
     * (which happens when subscriptions are routed differently or before the
     * runner has populated its subscriber list).
     */
    private List<String> resolveProcessorsForFeed(
            String group, String feed,
            Map<String, Map<String, List<EventSubscriptionKey<?>>>> subsByGroup) {
        Map<String, List<EventSubscriptionKey<?>>> procSubs = subsByGroup.get(group);
        ComposingEventProcessorAgentRunner runner = processorAgents.get(group);
        if (runner == null) return List.of();
        List<String> precise = new ArrayList<>();
        if (procSubs != null) {
            procSubs.forEach((procName, keys) -> {
                for (EventSubscriptionKey<?> k : keys) {
                    if (feed.equals(extractFeedName(k))) {
                        precise.add(procName);
                        break;
                    }
                }
            });
        }
        if (!precise.isEmpty()) return precise;
        // Fallback to group fanout when per-processor data hasn't populated yet.
        List<String> fallback = new ArrayList<>();
        for (NamedEventProcessor np : runner.group().registeredEventProcessors()) {
            fallback.add(np.name());
        }
        return fallback;
    }

    /**
     * Convert an {@link EventSubscriptionKey} to a typed
     * {@link SubscriptionInfo}. The key's accessors expose feed name and
     * callback class directly so no reflection is needed inside mongoose —
     * this is exactly the boundary an introspection service is supposed to
     * encapsulate.
     */
    static SubscriptionInfo toSubscriptionInfo(EventSubscriptionKey<?> key) {
        return new SubscriptionInfo(extractFeedName(key), extractCallback(key));
    }

    static String extractFeedName(EventSubscriptionKey<?> key) {
        if (key == null) return "";
        String s = String.valueOf(key);
        int slash = s.indexOf('/');
        return slash > 0 ? s.substring(0, slash) : s;
    }

    static String extractCallback(EventSubscriptionKey<?> key) {
        if (key == null) return "";
        String s = String.valueOf(key);
        int slash = s.lastIndexOf('/');
        String tail = slash >= 0 ? s.substring(slash + 1) : s;
        int dot = tail.lastIndexOf('.');
        return dot >= 0 ? tail.substring(dot + 1) : tail;
    }

}
