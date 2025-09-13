/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import lombok.extern.java.Log;

/**
 * Best-effort CPU core pinning support.
 * <p>
 * If the optional dependency net.openhft:affinity is on the classpath, this helper will
 * pin the current thread to the requested core via reflection.
 * Otherwise, it will log a message and perform no operation.
 */
@Log
public final class CoreAffinity {

    private CoreAffinity() {}

    /**
     * Attempt to pin the current thread to the supplied zero-based core index.
     * Returns true if a supported affinity library was found and invoked, else false.
     */
    public static boolean pinCurrentThreadToCore(int coreId) {
        try {
            // Try OpenHFT Affinity API via reflection to avoid hard dependency
            Class<?> affinityCls = Class.forName("net.openhft.affinity.Affinity");
            try {
                // Prefer direct setAffinity(int)
                var m = affinityCls.getMethod("setAffinity", int.class);
                m.invoke(null, coreId);
                log.info(() -> "Pinned thread '" + Thread.currentThread().getName() + "' to core " + coreId + " using OpenHFT Affinity");
                return true;
            } catch (NoSuchMethodException nsme) {
                // Fallback to acquiring a lock (common pattern)
                Class<?> lockCls = Class.forName("net.openhft.affinity.AffinityLock");
                var acquireCore = lockCls.getMethod("acquireLock", int.class);
                Object lock = acquireCore.invoke(null, coreId);
                // We deliberately do not release here to keep the pin for thread lifecycle.
                log.info(() -> "Pinned thread '" + Thread.currentThread().getName() + "' to core " + coreId + " using OpenHFT AffinityLock");
                return true;
            }
        } catch (Throwable t) {
            log.info(() -> "Core pin requested for core " + coreId + ", but no supported affinity library was found. Running unpinned.");
            return false;
        }
    }
}
