/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 */

package com.telamin.mongoose.service.admin.impl;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.mongoose.dispatch.AbstractEventToInvocationStrategy;

/**
 * Invocation strategy that executes AdminCommand events by calling executeCommand on receipt.
 */
@Experimental
public class AdminCommandInvoker extends AbstractEventToInvocationStrategy {

    /**
     * Create a new AdminCommandInvoker.
     */
    public AdminCommandInvoker() {
        super();
    }

    @Override
    protected void dispatchEvent(Object event, DataFlow eventProcessor) {
        AdminCommand adminCommand = (AdminCommand) event;
        adminCommand.executeCommand();
    }

    @Override
    protected boolean isValidTarget(DataFlow eventProcessor) {
        return true;
    }
}
