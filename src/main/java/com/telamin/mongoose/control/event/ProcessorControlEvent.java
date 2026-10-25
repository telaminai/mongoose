/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/**
 * The audit frame of a control action performed on a data processor (design "Record schema"): installing or removing an
 * audit capture, delivering or withdrawing a service. It names the action in the processor's own audit record and is
 * never dispatched to the processor's handlers: the action runs through {@code DataFlow.runInEventCycle}, as admin
 * commands do.
 */
public record ProcessorControlEvent(String action, String detail) {

    @Override
    public String toString() {
        return "control:" + action + (detail == null ? "" : " " + detail);
    }
}
