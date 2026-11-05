/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 */

package com.telamin.mongoose.dutycycle;

import com.telamin.mongoose.config.MongooseServerConfig.FatalErrorPolicy;
import lombok.extern.java.Log;
import org.agrona.ErrorHandler;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * The agent error handler {@code bootServer} installs (issue #77, A1). By default ({@link FatalErrorPolicy#CONTAIN}) it
 * logs and keeps the process alive. What then reports the failure depends on the agent:
 * <ul>
 *   <li>a <b>processor group</b>: an {@code Error} ends its thread, so the group is DEAD and
 *       {@code group:<name>/control} is DOWN; a control-plane failure while the thread lives fences its processors;</li>
 *   <li>an <b>agent-hosted service group</b> (feeds, workers, pipes on their own agent): an {@code Error} ends its
 *       thread and {@code agent-group:<name>/agent} is DOWN, with its services' {@code up} gauges at 0;</li>
 *   <li>any other throwable leaves the agent running (Agrona continues its loop); it is only logged.</li>
 * </ul>
 * A repeating failure is logged at SEVERE with its stack trace the first time, then at most once per
 * {@value #REPEAT_LOG_INTERVAL_MILLIS} ms with a count of the repeats suppressed. Exiting the JVM is an explicit opt-in
 * ({@link FatalErrorPolicy#EXIT_PROCESS}); a library never exits its host by default.
 */
@Log
public class GlobalErrorHandler implements ErrorHandler {

    /** How often a repeating failure (same class and message) is logged again under CONTAIN. */
    public static final long REPEAT_LOG_INTERVAL_MILLIS = 10_000;

    private final FatalErrorPolicy policy;
    private final IntConsumer exit;
    private final LongSupplier clockMillis;
    private final Map<String, long[]> repeats = new ConcurrentHashMap<>();   // key -> {lastLoggedMillis, suppressed}

    /** The default policy: contain. */
    public GlobalErrorHandler() {
        this(FatalErrorPolicy.CONTAIN);
    }

    public GlobalErrorHandler(FatalErrorPolicy policy) {
        this(policy, System::exit);
    }

    /** @param exit how the process exits under {@code EXIT_PROCESS} (replaceable for tests) */
    public GlobalErrorHandler(FatalErrorPolicy policy, IntConsumer exit) {
        this(policy, exit, System::currentTimeMillis);
    }

    GlobalErrorHandler(FatalErrorPolicy policy, IntConsumer exit, LongSupplier clockMillis) {
        this.policy = policy == null ? FatalErrorPolicy.CONTAIN : policy;
        this.exit = Objects.requireNonNull(exit, "exit");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
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
        String line = logLine(throwable);
        if (line != null) {
            log.log(Level.SEVERE, line, throwable);
        }
    }

    /** The CONTAIN log line for {@code throwable}, or null while a repeat of it is suppressed (package-private for tests). */
    String logLine(Throwable throwable) {
        String key = throwable.getClass().getName() + ":" + throwable.getMessage();
        long now = clockMillis.getAsLong();
        if (repeats.size() > 1024) repeats.clear();     // bounded: messages that vary forever never grow it without end
        long[] state = repeats.computeIfAbsent(key, k -> new long[]{Long.MIN_VALUE, 0});
        synchronized (state) {
            if (state[0] != Long.MIN_VALUE && now - state[0] < REPEAT_LOG_INTERVAL_MILLIS) {
                state[1]++;
                return null;
            }
            long suppressed = state[1];
            state[0] = now;
            state[1] = 0;
            return "problem dispatching events; the process stays up (fatalErrorPolicy CONTAIN): " + throwable.getMessage()
                    + (suppressed == 0 ? "" : " (" + suppressed + " repeats suppressed since the last report)");
        }
    }
}
