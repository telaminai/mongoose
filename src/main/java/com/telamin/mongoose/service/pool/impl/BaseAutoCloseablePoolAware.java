/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.pool.impl;

import com.telamin.mongoose.service.pool.AutoCloseablPoolAware;

/**
 * A base implementation class for managing AutoCloseable resources within an object pool.
 * This class extends BasePoolAware and implements AutoCloseablPoolAware to provide
 * foundational support for objects that need to be properly cleaned up when returned
 * to the pool. It is particularly useful for managing resources like database connections,
 * file handles, and network connections that require explicit cleanup.
 * <p>
 * Subclasses should implement specific cleanup logic for their managed resources
 * while leveraging the pooling infrastructure provided by this base class.
 *
 * @author Greg Higgins
 * @see BasePoolAware
 * @see AutoCloseablPoolAware
 */
public class BaseAutoCloseablePoolAware extends BasePoolAware implements AutoCloseablPoolAware {
}
