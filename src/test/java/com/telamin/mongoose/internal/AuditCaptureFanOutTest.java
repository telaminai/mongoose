/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.service.counters.MongooseCountersService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MA-5 — capture must fan out to the server's configured listener, and restore it on stop.
 *
 * <p><b>What was wrong.</b> {@code startRecording} set {@code previousListener = null} and installed
 * only its own listener, so capture REPLACED the configured one (MA-5a); {@code stopRecording} then
 * installed a no-op rather than restoring anything, so after stopping, audit reached neither the
 * console nor a file until the server restarted (MA-5b). The class comment promised both behaviours.
 *
 * <p><b>Why the listener arrives at attach.</b> {@code DataFlow} has no getter for the current
 * listener, and the server's own field is a {@code private static} overwritten by each
 * {@code bootServer}, so it must be captured per processor at attach or a second server in the same
 * JVM would have its listener restored onto the first's processor.
 */
class AuditCaptureFanOutTest {

    /** A DataFlow that only records what listener was last set on it. */
    private static DataFlow dataFlowCapturing(AtomicReference<LogRecordListener> installed) {
        return (DataFlow) Proxy.newProxyInstance(
                DataFlow.class.getClassLoader(), new Class<?>[]{DataFlow.class},
                (InvocationHandler) (proxy, method, args) -> {
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
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == void.class) return null;
        return 0;
    }

    /** A LogRecord whose text is fixed; enough for the sink to write something. */
    private static LogRecord record(String text) {
        LogRecord r = new LogRecord(new com.telamin.fluxtion.runtime.time.Clock());
        r.addRecord("probe", "msg", text);
        return r;
    }

    /** The shipped no-op counters service; a hand-rolled proxy for it was my own harness bug. */
    private static MongooseCountersService counters() {
        return NoOpCountersService.INSTANCE;
    }

    private static AuditCaptureConfig config(Path dir) {
        AuditCaptureConfig c = new AuditCaptureConfig();
        c.setEnabled(true);
        c.setDirectory(dir.toString());
        return c;
    }

    /** MA-5.1 and MA-5.2 — fan out while recording, restore on stop. */
    @Test
    void recordsReachBothDestinationsAndTheListenerIsRestoredOnStop(@TempDir Path dir) {
        List<String> console = new CopyOnWriteArrayList<>();
        LogRecordListener configured = r -> console.add(String.valueOf(r.asCharSequence()));
        AtomicReference<LogRecordListener> installed = new AtomicReference<>();
        DataFlow flow = dataFlowCapturing(installed);

        // The SERVER installs its configured listener, then attaches the capture service and hands it
        // that same listener. attach() does not install anything — modelling that was a harness bug.
        flow.setAuditLogProcessor(configured);
        ChronicleAuditCaptureService svc = new ChronicleAuditCaptureService(config(dir), counters());
        svc.attach(flow, "p", configured);
        assertSame(configured, installed.get(), "precondition: the server's listener is on the processor");

        svc.start("p");
        assertNotSame(configured, installed.get(), "capture installs its own listener while recording");

        installed.get().processLogRecord(record("while-recording"));
        assertEquals(1, console.size(),
                "MA-5.1: a record must reach the configured listener as well as the queue");

        svc.stop("p");
        assertSame(configured, installed.get(),
                "MA-5.2: stopping capture restores the configured listener, not a no-op");

        installed.get().processLogRecord(record("after-stop"));
        assertEquals(2, console.size(), "MA-5.2: records reach the listener again after stop");
    }

    /** MA-5.3 — start, stop, start: no double wrapping, one delivery per destination. */
    @Test
    void startStopStartDeliversExactlyOncePerDestination(@TempDir Path dir) {
        List<String> console = new CopyOnWriteArrayList<>();
        LogRecordListener configured = r -> console.add(String.valueOf(r.asCharSequence()));
        AtomicReference<LogRecordListener> installed = new AtomicReference<>();

        DataFlow flow = dataFlowCapturing(installed);
        flow.setAuditLogProcessor(configured);
        ChronicleAuditCaptureService svc = new ChronicleAuditCaptureService(config(dir), counters());
        svc.attach(flow, "p", configured);

        svc.start("p");
        svc.stop("p");
        svc.start("p");

        installed.get().processLogRecord(record("once"));
        assertEquals(1, console.size(),
                "MA-5.3: after start/stop/start the record must arrive ONCE, not twice — "
                        + "a re-wrapped listener would double it");
    }

    /** MA-5.5 — isolation both ways, and failures counted rather than swallowed. */
    @Test
    void aThrowingListenerDoesNotStopTheQueueReceivingTheRecord(@TempDir Path dir) {
        AtomicReference<LogRecordListener> installed = new AtomicReference<>();
        LogRecordListener throwing = r -> {
            throw new IllegalStateException("configured listener is broken");
        };

        ChronicleAuditCaptureService svc = new ChronicleAuditCaptureService(config(dir), counters());
        svc.attach(dataFlowCapturing(installed), "p", throwing);
        svc.start("p");

        assertDoesNotThrow(() -> installed.get().processLogRecord(record("isolated")),
                "MA-5.5: a throwing listener must not stop the other destination");
        assertTrue(svc.isRecording("p"), "and must not tear the sink down");
    }

    /** MA-5.6 — two servers in one JVM each restore their own listener. */
    @Test
    void twoServersEachRestoreTheirOwnListener(@TempDir Path dir) {
        LogRecordListener listenerA = r -> { };
        LogRecordListener listenerB = r -> { };
        AtomicReference<LogRecordListener> onA = new AtomicReference<>();
        AtomicReference<LogRecordListener> onB = new AtomicReference<>();

        ChronicleAuditCaptureService svcA = new ChronicleAuditCaptureService(config(dir.resolve("a")), counters());
        ChronicleAuditCaptureService svcB = new ChronicleAuditCaptureService(config(dir.resolve("b")), counters());

        svcA.attach(dataFlowCapturing(onA), "p", listenerA);
        svcB.attach(dataFlowCapturing(onB), "p", listenerB);
        svcA.start("p");
        svcB.start("p");

        svcA.stop("p");
        svcB.stop("p");

        assertSame(listenerA, onA.get(), "server A must get ITS listener back");
        assertSame(listenerB, onB.get(),
                "MA-5.6: server B must get its own — the server field is a static each boot overwrites, "
                        + "so reading it at stop would restore the wrong one");
    }

    /**
     * MA-5.4 — re-registration while recording.
     *
     * <p>The re-add path matters and is named here: {@code addEventProcessor} on a RUNNING server does
     * not call {@code init()}, so a re-add through it leaves the processor uninitialised; the
     * configuration path does call it. This drives the capture service's half — a re-attach must point
     * the sink at the NEW DataFlow and keep recording into the same file.
     */
    @Test
    void reAttachingWhileRecordingRedirectsToTheNewInstance(@TempDir Path dir) {
        List<String> console = new CopyOnWriteArrayList<>();
        LogRecordListener configured = r -> console.add(String.valueOf(r.asCharSequence()));
        AtomicReference<LogRecordListener> onFirst = new AtomicReference<>();
        AtomicReference<LogRecordListener> onSecond = new AtomicReference<>();

        ChronicleAuditCaptureService svc = new ChronicleAuditCaptureService(config(dir), counters());
        svc.attach(dataFlowCapturing(onFirst), "p", configured);
        svc.start("p");
        assertTrue(svc.isRecording("p"));

        // the processor is replaced while capture is still recording
        svc.attach(dataFlowCapturing(onSecond), "p", configured);
        assertTrue(svc.isRecording("p"), "capture is still recording after a re-attach");

        svc.stop("p");
        assertSame(configured, onSecond.get(),
                "MA-5.4: stop must restore the listener on the CURRENT DataFlow, not the replaced one");
    }
}
