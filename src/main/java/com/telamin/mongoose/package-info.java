/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * Package: server
 * <p>
 * Responsibility
 * - Entry-point orchestration and lifecycle management for the Fluxtion Server.
 * Hosts the MongooseServer class which wires configuration (config package),
 * event-flow (dispatch), agent groups (dutycycle), and services (service).
 * <p>
 * Public API
 * - MongooseServer boot helpers and server control interface exposure.
 * <p>
 * Allowed dependencies
 * - May depend on: config, dispatch, dutycycle, service, exception.
 * - Must not be referenced by those packages for core logic (avoid cycles); they
 * should remain orchestrated from here.
 */
package com.telamin.mongoose;