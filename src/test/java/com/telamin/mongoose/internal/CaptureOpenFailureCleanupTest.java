/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.service.audit.AuditCaptureIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CaptureOpenFailureCleanupTest {
    static class Flow implements DataFlow {
        public void onEvent(Object event) { }
        public void addEventFeed(EventFeed feed) { }
        public void setAuditLogProcessor(LogRecordListener listener) { }
    }
    static AuditCaptureConfig config(Path dir) {
        var cfg = new AuditCaptureConfig(); cfg.setEnabled(true); cfg.setDirectory(dir.toString()); return cfg;
    }
    @Test void failedScopedBuildRemovesOnlyItsFreshDirectory(@TempDir Path dir) throws Exception {
        Path history = Files.createDirectory(dir.resolve("history"));
        Files.writeString(history.resolve("existing.cq4"), "keep");
        var failedPath = new AtomicReference<Path>();
        var expected = new IllegalStateException("Chronicle build failed");
        var service = new ChronicleAuditCaptureService(config(dir), NoOpCountersService.INSTANCE, path -> {
            failedPath.set(path);
            try { Files.writeString(path.resolve("partial.cq4"), "partial"); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            throw expected;
        });
        var identity = new AuditCaptureIdentity("g", "p", 1);
        service.attachScoped(new Flow(), identity, r -> { }, Runnable::run);
        assertSame(expected, assertThrows(IllegalStateException.class, () -> service.openCaptureScoped(identity)));
        assertFalse(Files.exists(failedPath.get()), "failed open must not leave a directory or owner sidecar");
        assertEquals("keep", Files.readString(history.resolve("existing.cq4")));
        assertEquals(1, new DirAuditIntrospectionService(config(dir), service).listAvailable().size());
    }
    @Test void failedStandaloneBuildNeverDeletesExistingHistory(@TempDir Path dir) throws Exception {
        Path history = Files.createDirectory(dir.resolve("p"));
        Files.writeString(history.resolve("existing.cq4"), "keep");
        var service = new ChronicleAuditCaptureService(config(dir), NoOpCountersService.INSTANCE, path -> {
            throw new AssertionError("build failed");
        });
        service.attach(new Flow(), "p", r -> { });
        assertThrows(AssertionError.class, () -> service.start("p"));
        assertEquals("keep", Files.readString(history.resolve("existing.cq4")));
    }
}
