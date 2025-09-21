/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * Package: service
 * <p>
 * Responsibility
 * - Contains server-managed services and utilities supporting services:
 * - AbstractEventSourceService and scheduler integration.
 * - Admin service contracts and implementation (admin subpackage).
 * - Error reporting utilities (error subpackage).
 * - ServiceInjector (lightweight DI for server-managed services).
 * <p>
 * Public API (consumed by other packages)
 * - AbstractEventSourceService (for creating event sources as services)
 * - service.admin.AdminCommandRegistry and related SPI
 * - service.scheduler.SchedulerService
 * - ServiceInjector (utility)
 * <p>
 * Allowed dependencies
 * - May depend on: com.telamin.mongoose.dispatch (to interact with event flow),
 * com.telamin.mongoose.exception, com.telamin.fluxtion.runtime annotations/services.
 * - Must not depend on: com.telamin.mongoose.config (config is input to MongooseServer only).
 * <p>
 * Notes
 * - Keep services reusable and isolated from server orchestration concerns.
 */
package com.telamin.mongoose.service;