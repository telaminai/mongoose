/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.service.audit.AuditSinkHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The audit LISTING must not freeze while the file grows.
 *
 * <p><b>Measured on a booted server before this was fixed:</b> after a processor was re-registered, the
 * export grew from 23 records to 45 while {@code /api/audit/files} kept reporting {@code recordCount=23}
 * and a {@code lastWriteAt} from boot time.
 *
 * <p><b>Two causes, both pre-existing and neither in the re-registration path.</b>
 * <ol>
 *   <li>{@code DirAuditIntrospectionService}'s cache comment says it "is invalidated on each live-sink
 *       mutation reported by the capture service". Nothing reported one — {@code invalidate()} had no
 *       callers anywhere in {@code src/main} — so a sink that began recording after the first listing
 *       never appeared in it at all.</li>
 *   <li>A handle carries {@code recordCount} and {@code lastWriteAt}, which change on EVERY record, and
 *       serving them from a cached snapshot froze them. Invalidating per record would defeat the cache,
 *       so the live handle is overlaid on read while the directory walk stays cached.</li>
 * </ol>
 *
 * <p>This matters beyond the listing: OD-5 option (a) would have the Chronicle marker count from here.
 */
class AuditListingFreshnessTest {

    private static DataFlow dataFlowCapturing(AtomicReference<LogRecordListener> installed) {
        return (DataFlow) Proxy.newProxyInstance(
                DataFlow.class.getClassLoader(), new Class<?>[]{DataFlow.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setAuditLogProcessor":
                            installed.set((LogRecordListener) args[0]);
                            return null;
                        case "toString":
                            return "dataFlowStub";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            Class<?> t = method.getReturnType();
                            if (!t.isPrimitive()) return null;
                            if (t == boolean.class) return false;
                            if (t == void.class) return null;
                            return 0;
                    }
                });
    }

    private static LogRecord record(String text) {
        LogRecord r = new LogRecord(new com.telamin.fluxtion.runtime.time.Clock());
        r.addRecord("probe", "msg", text);
        return r;
    }

    private static AuditCaptureConfig config(Path dir) {
        AuditCaptureConfig c = new AuditCaptureConfig();
        c.setEnabled(true);
        c.setDirectory(dir.toString());
        return c;
    }

    private static long listedCount(DirAuditIntrospectionService svc, String processor) {
        return svc.listAvailable().stream()
                .filter(h -> processor.equals(h.processorName()))
                .mapToLong(AuditSinkHandle::recordCount)
                .findFirst().orElse(-1);
    }

    /** The counter in the LISTING advances as records are written. */
    @Test
    void theListedRecordCountAdvancesWithTheFile(@TempDir Path dir) {
        ChronicleAuditCaptureService capture =
                new ChronicleAuditCaptureService(config(dir), NoOpCountersService.INSTANCE);
        DirAuditIntrospectionService listing =
                new DirAuditIntrospectionService(config(dir), capture);

        AtomicReference<LogRecordListener> installed = new AtomicReference<>();
        capture.attach(dataFlowCapturing(installed), "p", r -> { });
        capture.start("p");

        installed.get().processLogRecord(record("one"));
        long afterOne = listedCount(listing, "p");
        assertEquals(1, afterOne, "the first listing must see the first record");

        installed.get().processLogRecord(record("two"));
        installed.get().processLogRecord(record("three"));

        assertEquals(3, listedCount(listing, "p"),
                "the listing FROZE here before the fix: the file grew and recordCount did not. "
                        + "Measured on a live server as 23 while the export held 45");
    }

    /** {@code lastWriteAt} must move too — it was stuck at boot time. */
    @Test
    void theListedLastWriteAdvances(@TempDir Path dir) throws InterruptedException {
        ChronicleAuditCaptureService capture =
                new ChronicleAuditCaptureService(config(dir), NoOpCountersService.INSTANCE);
        DirAuditIntrospectionService listing =
                new DirAuditIntrospectionService(config(dir), capture);

        AtomicReference<LogRecordListener> installed = new AtomicReference<>();
        capture.attach(dataFlowCapturing(installed), "p", r -> { });
        capture.start("p");
        installed.get().processLogRecord(record("one"));

        var first = listing.listAvailable().stream()
                .filter(h -> "p".equals(h.processorName())).findFirst().orElseThrow().lastWriteAt();

        Thread.sleep(5);
        installed.get().processLogRecord(record("two"));

        var second = listing.listAvailable().stream()
                .filter(h -> "p".equals(h.processorName())).findFirst().orElseThrow().lastWriteAt();

        assertTrue(second.isAfter(first),
                "lastWriteAt must advance; it was pinned to boot time: " + first + " -> " + second);
    }

    /**
     * A sink that starts recording AFTER the first listing must appear in it.
     *
     * <p>This is the half the missing {@code invalidate()} caused: the cached snapshot was taken before
     * the sink existed and never replaced.
     */
    @Test
    void aSinkThatStartsAfterTheFirstListingAppears(@TempDir Path dir) {
        ChronicleAuditCaptureService capture =
                new ChronicleAuditCaptureService(config(dir), NoOpCountersService.INSTANCE);
        DirAuditIntrospectionService listing =
                new DirAuditIntrospectionService(config(dir), capture);

        assertTrue(listing.listAvailable().isEmpty(), "nothing is recording yet");

        AtomicReference<LogRecordListener> installed = new AtomicReference<>();
        capture.attach(dataFlowCapturing(installed), "late", r -> { });
        capture.start("late");
        installed.get().processLogRecord(record("one"));

        assertEquals(1, listedCount(listing, "late"),
                "a sink that began after the first listing must appear — invalidate() had no callers, "
                        + "so the first snapshot was served for ever");
    }

    /** And the re-registration path keeps the counters moving (MA-5.4's listing half). */
    @Test
    void countersKeepAdvancingAfterAReRegistration(@TempDir Path dir) {
        ChronicleAuditCaptureService capture =
                new ChronicleAuditCaptureService(config(dir), NoOpCountersService.INSTANCE);
        DirAuditIntrospectionService listing =
                new DirAuditIntrospectionService(config(dir), capture);

        AtomicReference<LogRecordListener> first = new AtomicReference<>();
        capture.attach(dataFlowCapturing(first), "p", r -> { });
        capture.start("p");
        first.get().processLogRecord(record("before"));
        assertEquals(1, listedCount(listing, "p"));

        AtomicReference<LogRecordListener> second = new AtomicReference<>();
        capture.attach(dataFlowCapturing(second), "p", r -> { });
        second.get().processLogRecord(record("after-one"));
        second.get().processLogRecord(record("after-two"));

        assertEquals(3, listedCount(listing, "p"),
                "MA-5.4's listing half: after a re-registration the listed count must keep moving");
    }
}
