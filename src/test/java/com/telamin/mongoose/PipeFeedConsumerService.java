/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.input.NamedFeed;

/** Test helper for HandlerPipeConfigIntegrationTest. */
public class PipeFeedConsumerService {
    public volatile NamedFeed feed;
    public volatile String feedName;

    public PipeFeedConsumerService() {}

    @ServiceRegistered
    public void onFeed(NamedFeed feed, String name) {
        if ("test-pipe".equals(name)) {
            this.feed = feed;
            this.feedName = name;
        }
    }
}
