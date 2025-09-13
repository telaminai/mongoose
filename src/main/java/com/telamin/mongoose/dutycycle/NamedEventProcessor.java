/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 */

package com.telamin.mongoose.dutycycle;

import com.fluxtion.runtime.StaticEventProcessor;

public record NamedEventProcessor(String name, StaticEventProcessor eventProcessor) {
}
