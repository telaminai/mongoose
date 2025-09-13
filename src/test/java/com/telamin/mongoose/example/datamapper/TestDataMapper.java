/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example.datamapper;

import com.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.mongoose.service.pool.ObjectPoolsRegistry;
import lombok.Getter;

import java.util.function.Function;

public class TestDataMapper implements Function<TestEvent_In, TestEvent_Out> {
    @Getter
    private ObjectPoolsRegistry objectPoolsRegistry;

    @Override
    public TestEvent_Out apply(TestEvent_In testEventIn) {
        return new TestEvent_Out(testEventIn.getMessage());
    }

    @ServiceRegistered
    public void objectPool(ObjectPoolsRegistry objectPoolsRegistry, String name){
        this.objectPoolsRegistry = objectPoolsRegistry;
    }
}
