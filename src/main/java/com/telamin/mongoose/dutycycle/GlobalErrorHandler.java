/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 */

package com.telamin.mongoose.dutycycle;

import com.telamin.mongoose.config.MongooseServerConfig.FatalErrorPolicy;
import lombok.extern.java.Log;
import org.agrona.ErrorHandler;

import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.logging.Level;

/**
 * The agent error handler {@code bootServer} installs (issue #77, A1). By default ({@link FatalErrorPolicy#CONTAIN}) it
 * logs and keeps the process alive: an {@code Error} still ends the failing group's thread, so that group is DEAD and
 * reported (health {@code group:<name>/control} DOWN) while other groups keep serving. Exiting the JVM is an explicit
 * opt-in ({@link FatalErrorPolicy#EXIT_PROCESS}); a library never exits its host by default.
 */
@Log
public class GlobalErrorHandler implements ErrorHandler {

    private final FatalErrorPolicy policy;
    private final IntConsumer exit;

    /** The default policy: contain. */
    public GlobalErrorHandler() {
        this(FatalErrorPolicy.CONTAIN);
    }

    public GlobalErrorHandler(FatalErrorPolicy policy) {
        this(policy, System::exit);
    }

    /** @param exit how the process exits under {@code EXIT_PROCESS} (replaceable for tests) */
    public GlobalErrorHandler(FatalErrorPolicy policy, IntConsumer exit) {
        this.policy = policy == null ? FatalErrorPolicy.CONTAIN : policy;
        this.exit = Objects.requireNonNull(exit, "exit");
    }

    public FatalErrorPolicy policy() {
        return policy;
    }

    @Override
    public void onError(Throwable throwable) {
        if (policy == FatalErrorPolicy.EXIT_PROCESS) {
            log.log(Level.SEVERE, "problem dispatching events, exiting (fatalErrorPolicy EXIT_PROCESS): "
                    + throwable.getMessage(), throwable);
            exit.accept(-1);
            return;
        }
        log.log(Level.SEVERE, "problem dispatching events; the process stays up (fatalErrorPolicy CONTAIN): "
                + throwable.getMessage(), throwable);
    }
}
