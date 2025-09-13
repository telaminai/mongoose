/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.example.datamapper;

import lombok.Getter;

/**
 * Simple event used by the datamapper examples/tests.
 */
@Getter
public class TestEvent_In {
    private final String message;

    public TestEvent_In(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "TestEvent_In{" + message + '}';
    }
}
