/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.batch;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BatchDto {
    protected final List<Object> batchData = new ArrayList<>();

    public <T> void addBatchItem(T batchItem) {
        batchData.add(batchItem);
    }
}
