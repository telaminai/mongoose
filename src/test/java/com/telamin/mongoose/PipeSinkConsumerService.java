/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.output.MessageSink;

/** Test helper for HandlerPipeConfigIntegrationTest. Top-level because the
 *  config framework's Class.forName lookup flattens nested-class FQNs with
 *  '.' separators rather than '$', so inner-class shapes fail to load. */
public class PipeSinkConsumerService {
    public volatile MessageSink<Object> sink;
    public volatile String sinkName;

    public PipeSinkConsumerService() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    @ServiceRegistered
    public void onSink(MessageSink sink, String name) {
        // The pipe registers feed under "test-pipe" and sink under
        // "test-pipe.sink" (default convention; configurable via
        // HandlerPipeConfig.sinkName).
        if ("test-pipe.sink".equals(name)) {
            this.sink = sink;
            this.sinkName = name;
        }
    }
}
