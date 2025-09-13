/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.pool;

import com.telamin.mongoose.service.pool.impl.BasePoolAware;
import lombok.*;

@ToString
public class MappedPoolMessage extends BasePoolAware {

    @Getter
    @Setter
    private String value;

    public void reset(){
        this.value = null;
    }

}
