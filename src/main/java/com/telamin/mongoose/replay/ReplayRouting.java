package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.admin.impl.AdminCommand;

/** What a replay needs from its group: the configured route for each source (D2: the callback is configuration). */
@Experimental
public interface ReplayRouting {

    /**
     * The route this group's config made to deliver {@code source}'s inputs to {@code flow} by {@code route} (a callback
     * type's name), or null until it has. An empty {@code route} matches any, and is refused, by an exception naming the
     * candidates, when more than one route delivers {@code source} to {@code flow}: a replay never chooses one.
     */
    ReplayRoute routeFor(String source, String route, DataFlow flow);

    /** {@link #routeFor(String, String, DataFlow)} for an entry that names no route. */
    default ReplayRoute routeFor(String source, DataFlow flow) {
        return routeFor(source, "", flow);
    }

    /** How {@code source}'s publisher wraps its items, so an index is rebuilt as the processor received it. */
    EventSource.EventWrapStrategy wrapOf(String source);

    /** The admin command registered under {@code name} (by the replayed processor), or null until it is. */
    AdminCommand adminCommand(String name);

    /**
     * Stop the replayed processor {@code flow} as the recorded run did at this point (a {@code Stopped} entry), through
     * the group's control processor, bound to its registration. @return false if the group cannot (no such processor)
     * @throws IllegalStateException when the group's mailbox is full: the replay waits and retries
     */
    default boolean stopProcessor(DataFlow flow) {
        return false;
    }

    /**
     * Deliver to, or withdraw from, the replayed processor the service registered as {@code service}, as the recorded
     * run did at this point. A positive version must identify the currently live registry binding;
     * an unavailable historical incarnation or an unverified configured object cannot substitute for it.
     *
     * @return null once delivered; {@code "wait:<why>"} while it cannot be yet; otherwise why the replay cannot
     *         reproduce it (a version no longer available, a callback that failed), which stops the replay
     */
    default String deliverService(DataFlow flow, String service, boolean register, long version) {
        return "wait:service delivery is not supported by this group";
    }
}
