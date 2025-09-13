/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.fluxtion.runtime.audit.LogRecord;
import com.fluxtion.runtime.audit.LogRecordListener;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class TestLogRecordListener implements LogRecordListener {
    private final List<LogRecord> logRecords = new ArrayList<>();

    @Override
    public void processLogRecord(LogRecord logRecord) {
        if (logRecords.size() < 1000) logRecords.add(logRecord);
    }

}
