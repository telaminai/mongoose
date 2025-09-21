/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

import com.telamin.fluxtion.runtime.annotations.OnEventHandler;
import com.telamin.fluxtion.runtime.audit.EventLogNode;
import com.telamin.mongoose.test.HeartbeatEvent;

public class HeartBeatNode extends EventLogNode {

    @OnEventHandler
    public boolean heartBeat(HeartbeatEvent time) {
        long deltaMicros = (System.nanoTime() - time.getTimestamp()) / 1_000;
//        auditLog.info("eventCbDeltaMicros", deltaMicros)
//                .debug("debugMessage", deltaMicros);
//        System.out.println("deltaMicros " + deltaMicros);
        return true;
    }

}
