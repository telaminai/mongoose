/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.pool.impl;

import com.telamin.mongoose.service.pool.ObjectPoolsRegistry;

/**
 * Public facade for accessing the shared ObjectPool service and creating per-type pools.
 * This keeps the underlying implementation classes package-private.
 */
public final class Pools {

    private Pools() {
    }

    /**
     * Shared registry instance.
     */
    public static final ObjectPoolsRegistry SHARED = new GlobalObjectPool();
}
