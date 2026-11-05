/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.effect;

import com.telamin.mongoose.control.event.EffectResult;

/**
 * Performs one effect and says what happened. Implemented by the group. It may not decide whether to perform: if an
 * implementation ever asks "should I really release this?", the decision has leaked out of the control processor and
 * the audit stops describing the group.
 */
@FunctionalInterface
public interface EffectAdapter {

    /**
     * @return the result, never {@code null}; throwing is allowed and becomes a failed result
     */
    EffectResult perform(ControlEffect effect) throws Exception;
}
