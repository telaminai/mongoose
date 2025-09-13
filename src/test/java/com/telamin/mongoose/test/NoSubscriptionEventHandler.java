/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.test;

import com.fluxtion.runtime.node.ObjectEventHandlerNode;
import lombok.Getter;

public class NoSubscriptionEventHandler extends ObjectEventHandlerNode {

    @Getter
    private boolean invoked = false;

    @Override
    protected boolean handleEvent(Object event) {
        invoked = true;
        return super.handleEvent(event);
    }
}
