/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.audit;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Metadata describing one audit-log file. Returned by
 * {@link MongooseAuditIntrospectionService} for both currently-recording
 * captures and historical files in the audit directory.
 *
 * @param id            stable identifier for the file (URL-safe; used in
 *                      REST paths like {@code /api/audit/file/&#123;id&#125;}).
 *                      Derived from the underlying Chronicle cycle id /
 *                      filename. Stable across server restarts.
 * @param processorName the YAML processor name that produced (or is
 *                      producing) this file.
 * @param path          absolute path to the file on disk.
 * @param cycle         Chronicle cycle id; useful for retention math
 *                      and roll boundaries. {@code -1} for non-Chronicle
 *                      backends.
 * @param sizeBytes     current size in bytes; for a live sink this is a
 *                      point-in-time read.
 * @param recordCount   number of audit records currently in the file.
 *                      For a live sink this is a point-in-time read.
 *                      {@code -1} when the count is unknown without a
 *                      full file walk (the impl may defer this).
 * @param startedAt     instant when the first record was written.
 * @param lastWriteAt   instant of the most recent record write. For a
 *                      historical file this equals {@code endedAt}; for
 *                      a live sink it advances on every flush.
 * @param isLive        {@code true} iff a processor is currently
 *                      writing to this sink.
 */
public record AuditSinkHandle(
        String id,
        String processorName,
        Path path,
        long cycle,
        long sizeBytes,
        long recordCount,
        Instant startedAt,
        Instant lastWriteAt,
        boolean isLive
) {
}
