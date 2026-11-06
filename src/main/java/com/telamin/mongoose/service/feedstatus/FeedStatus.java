/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.feedstatus;

import com.telamin.fluxtion.runtime.event.Event;

import java.io.Serializable;

/**
 * A change of an event source's {@link FeedState}, at a position: {@code sequence} is the feed's publish sequence
 * number when the state was reported, the number of items the feed had published by then (docs/design/feed-caught-up.md).
 * <p>
 * The host reads it through {@code MongooseServer.feedStatus} and the {@code feed.status} admin command. A feed
 * configured with {@code feedStatusEvents} also delivers it, in order with the feed's items, to the processors
 * subscribed to the feed, as an ordinary event: a node handles it with {@code @OnEventHandler(filterString = "<feed>")}
 * on a {@code FeedStatus} parameter. It is then audited, and recorded and replayed, as any other input. Off by default,
 * so a processor sees no new event unless its feed asks for one.
 *
 * @param feedName the feed (event source service) name; also the event's filter string
 * @param state    the state the source reached
 * @param sequence the feed's publish sequence number when the state was reported
 * @param bounded  whether the source is finite: it ends in {@link FeedState#END_OF_STREAM}
 * @param detail   why, for a human: "file not found", "partial line pending"; may be null
 */
public record FeedStatus(String feedName, FeedState state, long sequence, boolean bounded, String detail)
        implements Event, Serializable {

    @Override
    public String filterString() {
        return feedName;
    }
}
