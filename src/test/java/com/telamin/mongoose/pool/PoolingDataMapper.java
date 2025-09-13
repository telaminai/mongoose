/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.pool;

import com.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.ObjectPoolsRegistry;
import lombok.Getter;

import java.util.function.Function;

public class PoolingDataMapper implements Function<PooledMessage, MappedPoolMessage> {

    @Getter
    private ObjectPool<MappedPoolMessage> pool;

    @ServiceRegistered
    public void registerObjectPool(ObjectPoolsRegistry objectPoolsRegistry, String name){
        this.pool = objectPoolsRegistry.getOrCreate(
                MappedPoolMessage.class,
                MappedPoolMessage::new,
                MappedPoolMessage::reset);
    }

    @Override
    public MappedPoolMessage apply(PooledMessage pooledMessage) {
        MappedPoolMessage mappedPoolMessage = pool.acquire();
        mappedPoolMessage.setValue(pooledMessage.value);
        return mappedPoolMessage;
    }
}
