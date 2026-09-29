package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.admin.impl.AdminCommand;

/** What a replay needs from its group: the configured route for each source (D2: the callback is configuration). */
@Experimental
public interface ReplayRouting {

    /** The route this group's config made to deliver {@code source}'s inputs to {@code flow}; null until it has. */
    ReplayRoute routeFor(String source, DataFlow flow);

    /** How {@code source}'s publisher wraps its items, so an index is rebuilt as the processor received it. */
    EventSource.EventWrapStrategy wrapOf(String source);

    /** The admin command registered under {@code name} (by the replayed processor), or null until it is. */
    AdminCommand adminCommand(String name);
}
