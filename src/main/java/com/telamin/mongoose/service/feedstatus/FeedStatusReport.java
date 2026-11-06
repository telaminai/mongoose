/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.feedstatus;

import java.util.List;

/**
 * The host's view of its feeds (docs/design/feed-caught-up.md): for each feed, the state its source last reported, at
 * which position, and whether every subscriber queue has dispatched up to that position. Returned by
 * {@code MongooseServer.feedStatus} and printed by the {@code feed.status --json} admin command.
 * <p>
 * {@code settled} is true when every reporting feed in the report is {@link FeedState#CAUGHT_UP} or
 * {@link FeedState#END_OF_STREAM} and drained. Feeds that report {@link FeedState#UNKNOWN} do not hold it back: they
 * are listed in {@code fallbackFeeds}, and the caller applies its own heuristic (a quiet period) to those feeds only.
 * Drained covers only work dispatched from the feed queues: output a service, a timer or a vendor callback produces on
 * its own thread can still follow, so a launcher keeps a short quiet period as a backstop.
 * <p>
 * {@code lossless} (docs/design/feed-delivery.md, section 7) is true when no feed in the report has dropped an item for
 * any subscriber queue, refused a publish, or acknowledged an item whose delivery-complete callback was given up on
 * ({@code completionFailures}, section 5b). {@code settled} says the run is complete, {@code lossless} whether it is
 * whole: a launcher stops on {@code settled} and fails the run when {@code lossless} is false.
 *
 * @param settled       every reporting feed caught up (or ended) and drained
 * @param lossless      no feed in the report has dropped or refused an item or given up on a delivery completion
 * @param fallbackFeeds feeds whose source cannot tell, sorted
 * @param feeds         every feed in the report, sorted by name
 */
public record FeedStatusReport(boolean settled, boolean lossless, List<String> fallbackFeeds, List<Feed> feeds) {

    public FeedStatusReport {
        fallbackFeeds = List.copyOf(fallbackFeeds);
        feeds = List.copyOf(feeds);
    }

    /** Build a report from its feeds, deriving {@code settled} and {@code fallbackFeeds}. */
    public static FeedStatusReport of(List<Feed> feeds) {
        boolean settled = true;
        boolean lossless = true;
        java.util.List<String> fallback = new java.util.ArrayList<>();
        for (Feed f : feeds) {
            lossless &= f.lossless();
            if (f.state() == FeedState.UNKNOWN) {
                fallback.add(f.feed());
            } else if (!f.settled()) {
                settled = false;
            }
        }
        return new FeedStatusReport(settled, lossless, fallback, feeds);
    }

    /** The feed named {@code name}, or null. */
    public Feed feed(String name) {
        for (Feed f : feeds) {
            if (f.feed().equals(name)) return f;
        }
        return null;
    }

    /**
     * One feed.
     *
     * @param feed        feed name
     * @param state       the state its source last reported
     * @param bounded     whether its source is finite
     * @param sequence    the feed's publish sequence number at that report
     * @param version     how many states the feed has reported; a change between two reads means it moved
     * @param detail      the source's reason, may be null
     * @param drained     every subscriber queue has dispatched up to the report's position
     * @param delivery    the feed's losses, pauses and acknowledgements (docs/design/feed-delivery.md)
     * @param subscribers the subscriber queues of the feed
     */
    public record Feed(String feed, FeedState state, boolean bounded, long sequence, long version, String detail,
                       boolean drained, Delivery delivery, List<Subscriber> subscribers) {
        public Feed {
            subscribers = List.copyOf(subscribers);
            delivery = delivery == null ? Delivery.NONE : delivery;
        }

        /**
         * Caught up (or ended), drained, and not stalled: a positioned feed whose consumers are gone, fenced or dead
         * holds its position and cannot complete (docs/design/feed-delivery.md section 7). Says nothing about losses:
         * see {@link #lossless()}.
         */
        public boolean settled() {
            return state.caughtUp() && drained && delivery.stalled() == null;
        }

        /**
         * No item of this feed was dropped for any subscriber queue, no publish was refused, and no item's
         * delivery-complete callback was given up on.
         */
        public boolean lossless() {
            return delivery.lossless();
        }
    }

    /**
     * A feed's delivery counters, cumulative since the feed's publisher was created.
     *
     * @param policy       the feed's effective overload policy (its default, before per-group overrides)
     * @param dropped      queue copies dropped (a full DROP_AFTER_SPIN queue, or an item that could not pause)
     * @param refused      BLOCK_WITH_DEADLINE publishes refused at the deadline or at shutdown
     * @param downgraded   publishes that met a full never-drop queue on a path that could neither wait nor pause, and so
     *                     used the drop policy (any loss is also in {@code dropped})
     * @param paused       the source is paused now: a never-drop queue is full and the source holds its position
     * @param pauses       how many times the source has paused (episodes)
     * @param acknowledged the highest sequence number acknowledged to the source's committer; 0 for a source that does
     *                     not track positions
     * @param pendingAcks  positioned items published and not yet acknowledged
     * @param consumers    subscriber queues of the feed that can consume now (not fenced, not dead)
     * @param stalled      why a positioned feed holds its position and cannot complete (no live consumer, a consumer
     *                     fenced or dead, a removed consumer's unprocessed items), or null
     * @param unterminated bytes after the last line terminator that a bounded source ended without publishing (a
     *                     once-read file whose last line has no newline): not an item, so neither a loss nor a hold;
     *                     0 for every other source (docs/design/feed-delivery.md section 7)
     * @param completionFailures delivery-complete callbacks ({@code DeliveryContext.onComplete}) skipped after the
     *                     reader's retry policy gave up: their items were acknowledged although the callback (typically
     *                     a durable save) never succeeded, so each is a loss (docs/design/feed-delivery.md section 5b).
     *                     A callback that failed and then succeeded on retry is not counted
     */
    public record Delivery(String policy, long dropped, long refused, long downgraded, boolean paused, long pauses,
                           long acknowledged, int pendingAcks, int consumers, String stalled, long unterminated,
                           long completionFailures) {
        public static final Delivery NONE = new Delivery("DROP_AFTER_SPIN", 0, 0, 0, false, 0, 0, 0, 0, null, 0, 0);

        /** As before {@code completionFailures} was reported: 0. */
        public Delivery(String policy, long dropped, long refused, long downgraded, boolean paused, long pauses,
                        long acknowledged, int pendingAcks, int consumers, String stalled, long unterminated) {
            this(policy, dropped, refused, downgraded, paused, pauses, acknowledged, pendingAcks, consumers, stalled,
                    unterminated, 0);
        }

        /** As before {@code unterminated} was reported: 0. */
        public Delivery(String policy, long dropped, long refused, long downgraded, boolean paused, long pauses,
                        long acknowledged, int pendingAcks, int consumers, String stalled) {
            this(policy, dropped, refused, downgraded, paused, pauses, acknowledged, pendingAcks, consumers, stalled, 0, 0);
        }

        /** Nothing dropped or refused, and no delivery completion given up on. */
        public boolean lossless() {
            return dropped == 0 && refused == 0 && completionFailures == 0;
        }
    }

    /**
     * One subscriber queue of a feed.
     *
     * @param queue      the binding name: {@code group/feed/callback}
     * @param position   the items the queue had been given when the state was reported
     * @param dispatched the items the queue's reader has taken and finished dispatching (or skipped after a failure)
     * @param drained    {@code dispatched >= position}
     */
    public record Subscriber(String queue, long position, long dispatched, boolean drained) {
    }

    /** A JSON object of every field, for a launcher polling {@code feed.status --json}. */
    public String toJson() {
        StringBuilder b = new StringBuilder("{\"settled\":").append(settled).append(",\"lossless\":").append(lossless)
                .append(",\"fallbackFeeds\":[");
        for (int i = 0; i < fallbackFeeds.size(); i++) {
            if (i > 0) b.append(',');
            quote(b, fallbackFeeds.get(i));
        }
        b.append("],\"feeds\":[");
        for (int i = 0; i < feeds.size(); i++) {
            Feed f = feeds.get(i);
            if (i > 0) b.append(',');
            b.append("{\"feed\":");
            quote(b, f.feed());
            b.append(",\"state\":\"").append(f.state()).append('"')
                    .append(",\"bounded\":").append(f.bounded())
                    .append(",\"sequence\":").append(f.sequence())
                    .append(",\"version\":").append(f.version())
                    .append(",\"detail\":");
            if (f.detail() == null) b.append("null");
            else quote(b, f.detail());
            Delivery d = f.delivery();
            b.append(",\"drained\":").append(f.drained())
                    .append(",\"settled\":").append(f.settled())
                    .append(",\"lossless\":").append(f.lossless())
                    .append(",\"policy\":\"").append(d.policy()).append('"')
                    .append(",\"dropped\":").append(d.dropped())
                    .append(",\"refused\":").append(d.refused())
                    .append(",\"downgraded\":").append(d.downgraded())
                    .append(",\"paused\":").append(d.paused())
                    .append(",\"pauses\":").append(d.pauses())
                    .append(",\"acknowledged\":").append(d.acknowledged())
                    .append(",\"pendingAcks\":").append(d.pendingAcks())
                    .append(",\"consumers\":").append(d.consumers())
                    .append(",\"stalled\":");
            if (d.stalled() == null) b.append("null");
            else quote(b, d.stalled());
            b.append(",\"unterminated\":").append(d.unterminated());
            b.append(",\"completionFailures\":").append(d.completionFailures());
            b.append(",\"subscribers\":[");
            for (int j = 0; j < f.subscribers().size(); j++) {
                Subscriber s = f.subscribers().get(j);
                if (j > 0) b.append(',');
                b.append("{\"queue\":");
                quote(b, s.queue());
                b.append(",\"position\":").append(s.position())
                        .append(",\"dispatched\":").append(s.dispatched())
                        .append(",\"drained\":").append(s.drained()).append('}');
            }
            b.append("]}");
        }
        return b.append("]}").toString();
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("feeds: settled=").append(settled).append(" lossless=").append(lossless);
        if (!fallbackFeeds.isEmpty()) b.append(" fallback=").append(fallbackFeeds);
        for (Feed f : feeds) {
            b.append("\n\t").append(f.feed()).append(": ").append(f.state())
                    .append(f.bounded() ? " bounded" : " unbounded")
                    .append(" seq=").append(f.sequence())
                    .append(" drained=").append(f.drained())
                    .append(" policy=").append(f.delivery().policy())
                    .append(" dropped=").append(f.delivery().dropped())
                    .append(" refused=").append(f.delivery().refused())
                    .append(" acknowledged=").append(f.delivery().acknowledged());
            if (f.delivery().paused()) b.append(" PAUSED");
            if (f.delivery().stalled() != null) b.append(" STALLED (").append(f.delivery().stalled()).append(')');
            if (f.delivery().unterminated() > 0) b.append(" unterminated=").append(f.delivery().unterminated());
            if (f.delivery().completionFailures() > 0) {
                b.append(" completionFailures=").append(f.delivery().completionFailures());
            }
            if (f.detail() != null) b.append(" (").append(f.detail()).append(')');
            for (Subscriber s : f.subscribers()) {
                b.append("\n\t\t").append(s.queue()).append(": ").append(s.dispatched()).append('/').append(s.position());
            }
        }
        return b.toString();
    }

    private static void quote(StringBuilder b, String raw) {
        b.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
