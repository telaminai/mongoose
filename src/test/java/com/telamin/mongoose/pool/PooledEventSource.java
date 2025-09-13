/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.pool;

import com.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.mongoose.service.extension.AbstractEventSourceService;
import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.ObjectPoolsRegistry;
import lombok.Getter;

public class PooledEventSource extends AbstractEventSourceService<PooledMessage> {
    @Getter
    private ObjectPool<PooledMessage> pool;

    public PooledEventSource() {
        super("pooledSource");
    }

    public void publish(PooledMessage msg) {
        if (output != null) {
            output.publish(msg);
        }
    }

    @ServiceRegistered
    public void setObjectPoolsRegistry(ObjectPoolsRegistry objectPoolsRegistry, String name) {
        this.pool = objectPoolsRegistry.getOrCreate(
                PooledMessage.class,
                PooledMessage::new,
                pm -> pm.value = null);
    }
}
