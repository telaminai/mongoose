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

    private final Object owner;
    private final String group;
    private final ProcessorRegistration registration;

    ProcessorHandle(Object owner, String group, ProcessorRegistration registration) {
        this.owner = owner;
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

    boolean belongsTo(Object owner) {
        return this.owner == owner;
    }

    ProcessorRegistration registration() {
        return registration;
    }

    @Override
    public String toString() {
        return "ProcessorHandle{" + group + "/" + name() + " gen=" + generation() + "}";
    }
}
