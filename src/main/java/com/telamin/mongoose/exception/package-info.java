/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * Package: exception
 * <p>
 * Responsibility
 * - Defines Fluxtion Server's domain exception hierarchy used across packages.
 * <p>
 * Public API
 * - FluxtionServerException (base), ConfigurationException, ServiceRegistrationException,
 * AdminCommandException, QueuePublishException.
 * <p>
 * Allowed dependencies
 * - No dependencies on other server packages (pure domain exceptions).
 */
package com.telamin.mongoose.exception;