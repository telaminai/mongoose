/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.exception;

/**
 * Thrown on failures during service registration to the MongooseServer.
 */
public class ServiceRegistrationException extends FluxtionServerException {
    public ServiceRegistrationException(String message) {
        super(message);
    }
}
