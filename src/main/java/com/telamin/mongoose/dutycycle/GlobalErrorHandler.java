/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 */

package com.telamin.mongoose.dutycycle;

import com.fluxtion.agrona.ErrorHandler;
import lombok.extern.java.Log;

import java.util.logging.Level;

@Log
public class GlobalErrorHandler implements ErrorHandler {

    @Override
    public void onError(Throwable throwable) {
        log.log(Level.WARNING, "problem dispatching events error:" + throwable.getMessage(), throwable);
        System.exit(-1);
    }
}
