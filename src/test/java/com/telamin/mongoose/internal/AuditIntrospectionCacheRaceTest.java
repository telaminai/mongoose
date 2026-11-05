/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.config.AuditCaptureConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AuditIntrospectionCacheRaceTest {
    private static class Flow implements DataFlow {
        @Override public void onEvent(Object event) { }
        @Override public void addEventFeed(EventFeed feed) { }
        @Override public void setAuditLogProcessor(LogRecordListener listener) { }
    }
    private static AuditCaptureConfig config(Path dir) {
        var cfg = new AuditCaptureConfig();
        cfg.setEnabled(true);
        cfg.setDirectory(dir.toString());
        return cfg;
    }

    @Test void aNewCaptureInvalidatingAnInFlightWalkMustAppearOnTheNextRead(@TempDir Path dir) {
        var cfg = config(dir);
        var capture = new ChronicleAuditCaptureService(cfg, NoOpCountersService.INSTANCE);
        capture.attach(new Flow(), "p", r -> { });
        var first = new AtomicBoolean(true);
        var introspection = new DirAuditIntrospectionService(cfg, capture, () -> {
            if (first.compareAndSet(true, false)) capture.start("p");
        });
        try {
            introspection.listAvailable(); // walk saw no file; capture started before its result was published
            var next = introspection.listAvailable();
            assertEquals(1, next.size(), "an invalidated walk must not reinstall an empty cache permanently");
            assertTrue(next.getFirst().isLive());
            assertEquals("p", next.getFirst().processorName());
        } finally { capture.stopAll(); }
    }

    @Test void aStoppedCaptureInvalidatingAnInFlightWalkMustNotRemainLive(@TempDir Path dir) {
        var cfg = config(dir);
        var capture = new ChronicleAuditCaptureService(cfg, NoOpCountersService.INSTANCE);
        capture.attach(new Flow(), "p", r -> { });
        capture.start("p");
        var first = new AtomicBoolean(true);
        var introspection = new DirAuditIntrospectionService(cfg, capture, () -> {
            if (first.compareAndSet(true, false)) capture.stop("p");
        });
        try {
            introspection.listAvailable(); // walk saw a live handle; stop invalidated before publication
            var next = introspection.listAvailable();
            assertEquals(1, next.size());
            assertFalse(next.getFirst().isLive(), "a retired live snapshot must not survive its invalidation");
        } finally { capture.stopAll(); }
    }
}
