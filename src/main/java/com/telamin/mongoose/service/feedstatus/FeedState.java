/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.feedstatus;

/**
 * How far an event source has got through what is available to it (docs/design/feed-caught-up.md). Whether the source
 * is finite is a property of the source ({@link FeedStatus#bounded()}), not a state: an unbounded source at its head
 * (a tailed file at its end, a Kafka consumer with no lag) is {@link #CAUGHT_UP}.
 */
public enum FeedState {
    /**
     * The source cannot tell, or does not report (every source that predates feed status, JMS, a multicast feed). A host
     * waiting for the feed falls back to its own heuristic, a quiet period, for this feed only.
     */
    UNKNOWN,
    /** More is available than the source has published: it is reading, or has seen more arrive since it caught up. */
    BEHIND,
    /**
     * The source has published everything currently available, up to {@link FeedStatus#sequence()}. More may arrive
     * later, and the source then reports {@link #BEHIND} before publishing it.
     */
    CAUGHT_UP,
    /** A bounded source has published everything it ever will, up to {@link FeedStatus#sequence()}. Final. */
    END_OF_STREAM;

    /** Whether a feed in this state has published everything available to it, so it is settled once drained. */
    public boolean caughtUp() {
        return this == CAUGHT_UP || this == END_OF_STREAM;
    }
}
