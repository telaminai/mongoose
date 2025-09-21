/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.batch;

import com.telamin.fluxtion.runtime.annotations.OnEventHandler;
import com.telamin.fluxtion.runtime.node.BaseNode;

public class BatchDtoHandler extends BaseNode {

    @OnEventHandler
    public boolean processBatch(BatchDto batchEvent) {
        auditLog.debug("redispatchBatch", batchEvent);
        for (Object eventDto : batchEvent.getBatchData()) {
            auditLog.debug("redispatchEvent", eventDto);
            context.getParentDataFlow().onEvent(eventDto);
        }
        return false;
    }
}
