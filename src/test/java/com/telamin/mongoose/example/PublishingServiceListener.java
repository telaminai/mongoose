/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

/**
 * Strongly-typed callback interface that a processor can implement to receive
 * events from a PublishingServiceTyped via a custom EventToInvokeStrategy.
 */
public interface PublishingServiceListener {
    void onServiceEvent(String event);
}
