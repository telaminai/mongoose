/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose;

import com.telamin.mongoose.dutycycle.ProcessorRegistration;

/**
 * Identifies one accepted add of a processor: group, name and generation. A stop made with a handle can only ever stop
 * that registration, never a later processor added under the same name (design rule 7).
 */
public final class ProcessorHandle {

    private final String group;
    private final ProcessorRegistration registration;

    ProcessorHandle(String group, ProcessorRegistration registration) {
        this.group = group;
        this.registration = registration;
    }

    public String group() {
        return group;
    }

    public String name() {
        return registration.name();
    }

    public long generation() {
        return registration.generation();
    }

    ProcessorRegistration registration() {
        return registration;
    }

    @Override
    public String toString() {
        return "ProcessorHandle{" + group + "/" + name() + " gen=" + generation() + "}";
    }
}
