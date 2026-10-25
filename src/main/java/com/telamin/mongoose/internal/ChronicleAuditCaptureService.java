/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.service.audit.AuditSinkHandle;
import com.telamin.mongoose.service.audit.AuditCaptureIdentity;
import com.telamin.mongoose.service.audit.MongooseAuditCaptureService;
import com.telamin.mongoose.service.counters.MongooseCounter;
import com.telamin.mongoose.service.counters.MongooseCountersService;
import lombok.extern.java.Log;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Chronicle-Queue-backed implementation of
 * {@link MongooseAuditCaptureService}. Each recording processor writes
 * its audit log into a dedicated Chronicle queue directory under
 * {@code performanceMonitoring.auditCapture.directory}.
 *
 * <p><b>Wire format.</b> Each Fluxtion {@link LogRecord} arrives at the
 * listener with its YAML representation already assembled in the
 * record's internal {@code StringBuilder} (the Fluxtion runtime
 * formats it once per event for whatever consumer is wired). We write
 * that {@link CharSequence} directly into the Chronicle excerpt via
 * {@code DocumentContext.wire().getValueOut().text(cs)} — Chronicle's
 * {@code Bytes} accept a {@code CharSequence} without re-allocating.
 * Net agent-thread allocation per record: the {@code DocumentContext}
 * closeable (recycled by Chronicle thread-locals) and nothing else of
 * substance.
 *
 * <p>The on-disk format is therefore Chronicle excerpts where each
 * excerpt's text payload is a single YAML document — the exact shape
 * the desktop fluxtion-visualiser and the new svc-admin-web Replay tab
 * both expect. Phase 2's {@code /api/audit/file/&#123;id&#125;/export}
 * endpoint walks excerpts and emits either `---`-separated YAML (drop-
 * in for the desktop visualiser) or one NDJSON record per line (after
 * a server-side YAML→JSON parse).
 *
 * <p><b>Composition with the existing log sink.</b> Mongoose's
 * {@code MongooseServer} already installs a default
 * {@link LogRecordListener} (SLF4J INFO) on every processor. To avoid
 * silencing that path when capture starts, this service composes:
 * when {@code start(name)} runs, the previous listener is captured and
 * a new fan-out listener (audit-Chronicle then previous-listener) is
 * installed via {@code dataFlow.setAuditLogProcessor(...)}. On
 * {@code stop} the previous listener is restored.
 */
@Log
public final class ChronicleAuditCaptureService implements MongooseAuditCaptureService,
        com.telamin.mongoose.service.audit.AuditCaptureResources {

    private final AuditCaptureConfig config;
    private final MongooseCountersService counters;
    private final Object attachmentLock = new Object();
    private final ConcurrentHashMap<String, ProcessorSink> sinks = new ConcurrentHashMap<>(); // standalone compatibility
    private final ConcurrentHashMap<AuditCaptureIdentity, ProcessorSink> ownedSinks = new ConcurrentHashMap<>();
    private final java.util.Map<DataFlow, ProcessorSink> legacyAttachments = new java.util.IdentityHashMap<>();
    // Bounded diagnostics only: retired processors and their listeners are never retained here.
    private final java.util.Map<String, Boolean> retiredSelectors = new java.util.LinkedHashMap<>();

    private void rememberRetired(ProcessorSink sink) {
        synchronized (retiredSelectors) {
            retiredSelectors.put(sink.processorName, Boolean.TRUE);
            retiredSelectors.put(sink.selector(), Boolean.TRUE);
            while (retiredSelectors.size() > 512) retiredSelectors.remove(retiredSelectors.keySet().iterator().next());
        }
    }

    private boolean recentlyRetired(String selector) {
        synchronized (retiredSelectors) { return retiredSelectors.containsKey(selector); }
    }

    private java.util.List<ProcessorSink> attachedSinks() {
        var all = new java.util.ArrayList<>(sinks.values());
        all.addAll(ownedSinks.values());
        synchronized (legacyAttachments) { all.addAll(legacyAttachments.values()); }
        return all;
    }

    /** Resolve a selector only while unique; an old resource is never looked up by name. */
    private ProcessorSink selectedSink(String selector) {
        ProcessorSink found = null;
        for (ProcessorSink sink : attachedSinks()) {
            if (!sink.isAttached() && !sink.isRecording()) continue;
            int slash = selector.indexOf('/');
            boolean match = sink.identity != null && slash >= 0
                    ? sink.identity.group().equals(AuditCaptureIdentity.decode(selector.substring(0, slash)))
                        && sink.identity.name().equals(AuditCaptureIdentity.decode(selector.substring(slash + 1)))
                    : sink.processorName.equals(selector);
            if (!match) continue;
            if (found != null && found != sink) throw new IllegalArgumentException("ambiguous audit processor '"
                    + selector + "': use an encoded group/name selector");
            found = sink;
        }
        return found;
    }

    /** A single live lookup accepts the same selectors as start/stop. */
    public AuditSinkHandle currentSink(String selector) {
        ProcessorSink sink = selectedSink(selector);
        return sink != null && sink.isRecording() ? sink.handle() : null;
    }

    /**
     * Design "Audit capture" (control-plane PR 5): once a server routes capture through its processor groups, start and
     * stop are requests to the group's control processor, which opens, installs, uninstalls and closes through the
     * {@link com.telamin.mongoose.service.audit.AuditCaptureResources} methods below. Unrouted (no server, as in a unit
     * test), the service changes the sink itself, as before.
     */
    public interface CaptureRouter {
        /** Send an audit start or stop for the processor admitted as {@code processorName}; null if none is. */
        com.telamin.mongoose.control.ControlRequest send(String processorName, boolean start);

        /** Send only to the specified registration. Legacy routers may decline scoped requests. */
        default com.telamin.mongoose.control.ControlRequest sendScoped(AuditCaptureIdentity identity, boolean start) {
            throw new UnsupportedOperationException("router does not support registration-scoped capture");
        }

        /** Whether the calling thread may wait for a reply from that processor's group. */
        boolean safeToWait(String processorName);
    }

    private volatile CaptureRouter router;
    /** Opened resources not yet closed: whoever removes one closes it, so each is closed exactly once. */
    private final java.util.Set<CaptureHandle> openResources = ConcurrentHashMap.newKeySet();

    /** A capture resource: the queue opened for a processor's sink. Opaque to the group that holds it. */
    private static final class CaptureHandle {
        private volatile ProcessorSink sink;
        private final ChronicleQueue queue;
        CaptureHandle(ProcessorSink sink, ChronicleQueue queue) { this.sink = sink; this.queue = queue; }
        ProcessorSink sink() { return sink; }
        ChronicleQueue queue() { return queue; }
        synchronized void bind(ProcessorSink target) {
            if (sink != null && sink != target) throw new IllegalArgumentException("capture belongs to another registration");
            sink = target;
        }
    }

    public void routeThrough(CaptureRouter router) {
        this.router = router;
    }

    /** Told when the set of live sinks changes, so a listing cache can drop its snapshot. */
    private volatile Runnable liveSinkMutation = () -> { };

    /**
     * Register a callback for live-sink mutations — start, stop, and adopting a re-registered processor.
     *
     * <p>{@code DirAuditIntrospectionService}'s cache comment already claimed this existed. It did not,
     * so a sink that began recording after the first listing never appeared in it.
     */
    public void onLiveSinkMutation(Runnable listener) {
        this.liveSinkMutation = listener == null ? () -> { } : listener;
    }

    private final java.util.function.Function<Path, ChronicleQueue> queueBuilder;

    public ChronicleAuditCaptureService(AuditCaptureConfig config, MongooseCountersService counters) {
        this(config, counters, path -> SingleChronicleQueueBuilder.binary(path).rollCycle(RollCycles.FAST_DAILY).build());
    }

    ChronicleAuditCaptureService(AuditCaptureConfig config, MongooseCountersService counters,
                                java.util.function.Function<Path, ChronicleQueue> queueBuilder) {
        this.queueBuilder = java.util.Objects.requireNonNull(queueBuilder);
        this.config = config;
        this.counters = counters;
        try {
            Files.createDirectories(Path.of(config.getDirectory()));
        } catch (IOException e) {
            log.log(Level.WARNING,
                    "audit-capture directory could not be created at " + config.getDirectory() + ": " + e.getMessage());
        }
    }

    @Override
    public void attach(DataFlow dataFlow, String processorName) {
        attach(dataFlow, processorName, null);
    }

    @Override
    public void attach(DataFlow dataFlow, String processorName, LogRecordListener configuredListener) {
        attach(dataFlow, processorName, configuredListener, Runnable::run);
    }

    @Override
    public void attach(DataFlow dataFlow, String processorName, LogRecordListener configuredListener,
                       java.util.concurrent.Executor onAgentThread) {
        if (router != null) {
            synchronized (attachmentLock) {
                synchronized (legacyAttachments) {
                    ProcessorSink existing = legacyAttachments.get(dataFlow);
                    if (existing != null) {
                        if (!existing.processorName.equals(processorName) || existing.configuredListener != configuredListener) {
                            throw new IllegalStateException("processor already attached with another name or listener");
                        }
                        return;
                    }
                    requireUnownedFlow(dataFlow, null);
                    ProcessorSink sink = new ProcessorSink(dataFlow, processorName, null, true);
                    sink.configuredListener = configuredListener;
                    sink.onAgentThread = onAgentThread;
                    legacyAttachments.put(dataFlow, sink);
                }
            }
            return;
        }
        ProcessorSink sink;
        for (;;) {
            sink = sinks.computeIfAbsent(processorName, n -> new ProcessorSink(null, n));
            // Never acquire a sink monitor while holding the admission lock: listener callbacks can re-enter attach.
            synchronized (sink) {
                synchronized (attachmentLock) {
                    if (sinks.get(processorName) != sink) continue;
                    requireUnownedFlow(dataFlow, sink);
                    sink.reattach(dataFlow, configuredListener, onAgentThread);
                }
            }
            break;
        }
        // User/agent callbacks happen outside the attachment admission lock.
        sink.reinstallCapture();
        liveSinkMutation.run();
    }

    private void requireUnownedFlow(DataFlow flow, ProcessorSink allowed) {
        for (ProcessorSink other : attachedSinks()) {
            if (other != allowed && other.dataFlow == flow) {
                throw new IllegalStateException("processor instance already belongs to capture owner " + other.selector());
            }
        }
    }

    @Override
    public void startScoped(AuditCaptureIdentity identity) {
        CaptureRouter routed = router;
        if (routed != null) routedChange(routed, identity.selector(), true, routed.sendScoped(identity, true));
        else {
            startSink(ownedSinks.get(identity), identity.selector());
        }
    }

    @Override
    public void attachScoped(DataFlow flow, AuditCaptureIdentity identity, LogRecordListener listener,
                       java.util.concurrent.Executor executor) {
        synchronized (attachmentLock) {
            requireUnownedFlow(flow, null);
            ProcessorSink sink = new ProcessorSink(flow, identity.name(), identity, true);
            sink.configuredListener = listener;
            sink.onAgentThread = executor;
            if (ownedSinks.putIfAbsent(identity, sink) != null) {
                throw new IllegalStateException("capture registration already attached: " + identity);
            }
        }
    }

    /**
     * Begin capturing {@code processorName}.
     *
     * <p><b>Threading (mongoose#46).</b> Called from any thread, typically the admin transport's. The Chronicle queue
     * is opened here, on the caller's thread, so no file I/O lands on the processor's thread; the listener is then
     * installed on the processor's agent thread, and this returns once it has been. It is refused, by name, with an
     * {@link IllegalStateException} when that cannot happen in time (the group's thread is busy or wedged for
     * {@value #APPLY_TIMEOUT_SECONDS} s, its hand-off queue stays full, or the group has closed), and with an
     * {@link IllegalArgumentException} when the processor has left its group; nothing changes then. No lock is held
     * while waiting, so the agent thread is never blocked by a caller that waits on it. Called on the agent thread, or
     * before its group has started, it runs inline.
     */
    @Override
    public void start(String processorName) {
        CaptureRouter routed = router;
        if (routed != null) {
            routedChange(routed, processorName, true);
            return;
        }
        startSink(selectedSink(processorName), processorName);
    }

    private void startSink(ProcessorSink sink, String processorName) {
        if (sink == null) {
            throw new IllegalArgumentException(
                    "no processor named '" + processorName + "' attached to the audit-capture service");
        }
        if (sink.isRecording()) {
            // Idempotent — don't roll the sink. A sink only reads as recording once its listener is installed.
            return;
        }
        sink.requireAttached("start");
        ChronicleQueue opened = sink.openQueue(config, queueBuilder);
        boolean[] adopted = {false};
        try {
            sink.applyOnAgent("start", () -> adopted[0] = sink.beginRecording(opened, counters));
        } finally {
            if (!adopted[0]) opened.close();            // refused, cancelled, or another start got there first
        }
        if (adopted[0]) {
            liveSinkMutation.run();
            log.info("audit-capture STARTED for processor '" + processorName + "' at " + sink.path());
        }
    }

    /**
     * Halt capture of {@code processorName}: the listener the server configured is restored on the processor's agent
     * thread (mongoose#46), and the queue is closed once it has been, so no record in flight meets a closed queue.
     * Threading, bounds and refusals as {@link #start}. A processor that has left its group has nothing to restore;
     * its capture is closed here.
     */
    @Override
    public void stop(String processorName) {
        CaptureRouter routed = router;
        if (routed != null) {
            routedChange(routed, processorName, false);
            return;
        }
        ProcessorSink sink = selectedSink(processorName);
        if (sink == null || !sink.isRecording()) {
            return;
        }
        ChronicleQueue released = sink.endRecordingIfGone();    // processor gone: no DataFlow to touch, no thread to wait on
        if (released == null && sink.isAttached()) {
            released = sink.applyOnAgent("stop", sink::endRecording);
        }
        if (released != null) {
            released.close();
            liveSinkMutation.run();
            log.info("audit-capture STOPPED for processor '" + processorName + "'");
        }
    }

    /**
     * A start or stop as a request to the processor's group: waited for where the caller may wait, bounded as before; a
     * request the group has not begun applying when the bound runs out is cancelled, so nothing changes later.
     */
    private void routedChange(CaptureRouter routed, String processorName, boolean start) {
        routedChange(routed, processorName, start, routed.send(processorName, start));
    }

    private void routedChange(CaptureRouter routed, String processorName, boolean start,
                              com.telamin.mongoose.control.ControlRequest request) {
        String what = start ? "start" : "stop";
        if (request == null) {
            if (!start) return;                 // nothing admitted under the name: nothing records it
            if (sinks.containsKey(processorName) || recentlyRetired(processorName)) {
                throw new IllegalArgumentException("audit start for '" + processorName
                        + "' refused: the processor has left its group");
            }
            throw new IllegalArgumentException("no processor named '" + processorName
                    + "' attached to the audit-capture service");
        }
        if (!routed.safeToWait(processorName)) {
            // an agent thread, or a group not running yet: it applies later; its outcome is logged, never lost
            request.reply().whenComplete((r, e) -> {
                if (e != null || r == null || !r.applied()) {
                    log.warning("audit " + what + " for '" + processorName + "' was not applied: " + (e != null ? e : r));
                }
            });
            return;
        }
        com.telamin.mongoose.control.ControlReply reply =
                com.telamin.mongoose.control.ControlReplies.await(request, TimeUnit.SECONDS.toMillis(APPLY_TIMEOUT_SECONDS));
        if (reply == null && Thread.currentThread().isInterrupted()) {
            // interrupted, not timed out: the request still applies; nothing is cancelled (final review, finding 7)
            throw new IllegalStateException("audit " + what + " for '" + processorName + "': interrupted while waiting"
                    + " for it to be applied; it still applies");
        }
        if (reply == null) {
            if (request.cancel("audit " + what + " not applied within " + APPLY_TIMEOUT_SECONDS + "s")) {
                throw new IllegalStateException("audit " + what + " for '" + processorName + "' was not applied on its"
                        + " agent thread within " + APPLY_TIMEOUT_SECONDS + "s before it started; it was cancelled, and"
                        + " nothing changed");
            }
            // being applied: an open takes what it takes, bounded by its own timeout
            reply = com.telamin.mongoose.control.ControlReplies.await(request, OPEN_WAIT_MILLIS);
            if (reply == null) {
                throw new IllegalStateException("audit " + what + " for '" + processorName + "' is still being applied"
                        + " after " + OPEN_WAIT_MILLIS + " ms");
            }
        }
        switch (reply.outcome()) {
            case APPLIED -> { }
            case NOT_RUNNING -> {
                // the group has ended, and closed its captures as it did: nothing records the processor any more
                if (start) throw new IllegalStateException("audit start for '" + processorName + "' refused: its group"
                        + " is not running (" + reply.reason() + ")");
            }
            case REFUSED -> {
                if ("no such processor".equals(reply.reason())) {
                    throw new IllegalArgumentException("audit " + what + " for '" + processorName
                            + "' refused: the processor has left its group");
                }
                throw new IllegalStateException("audit " + what + " for '" + processorName + "' refused: " + reply.reason());
            }
            default -> throw new IllegalStateException("audit " + what + " for '" + processorName + "' "
                    + reply.outcome() + ": " + reply.reason());
        }
    }

    /** How long a routed start waits once its open is in progress: the group's open timeout, with a margin. */
    static final long OPEN_WAIT_MILLIS = 35_000;

    // ------------------------------------------------------------------ AuditCaptureResources: the group's hands

    @Override
    public Object openCapture(String processorName) {
        // Legacy routed callers supply the exact instance only at install. Never guess its owner or share its path.
        ProcessorSink sink = router == null
                ? sinks.computeIfAbsent(processorName, n -> new ProcessorSink(null, n)) : null;
        ProcessorSink opener = sink == null ? new ProcessorSink(null, processorName, null, true) : sink;
        CaptureHandle handle = new CaptureHandle(sink, opener.openQueue(config, queueBuilder));
        openResources.add(handle);
        return handle;
    }

    @Override
    public Object openCaptureScoped(AuditCaptureIdentity identity) {
        ProcessorSink sink = ownedSinks.get(identity);
        if (sink == null) {
            // Direct group embeddings may still use the original attach API. Defer exact instance binding;
            // do not fall back to a different scoped generation with the same name.
            boolean legacy = sinks.containsKey(identity.name());
            synchronized (legacyAttachments) {
                legacy |= legacyAttachments.values().stream().anyMatch(s -> s.processorName.equals(identity.name()));
            }
            if (!legacy) throw new IllegalArgumentException("capture registration is not attached: " + identity);
            var opener = new ProcessorSink(null, identity.name(), identity, true);
            CaptureHandle handle = new CaptureHandle(null, opener.openQueue(config, queueBuilder));
            openResources.add(handle);
            return handle;
        }
        sink.requireAttached("open");
        CaptureHandle handle = new CaptureHandle(sink, sink.openQueue(config, queueBuilder));
        openResources.add(handle);
        return handle;
    }

    @Override
    public void installCapture(Object resource, String processorName, DataFlow dataFlow) {
        CaptureHandle handle = (CaptureHandle) resource;
        if (!openResources.contains(handle)) throw new IllegalArgumentException("capture is closed or belongs to another service");
        ProcessorSink sink = handle.sink();
        if (sink == null) {
            synchronized (legacyAttachments) { sink = legacyAttachments.get(dataFlow); }
            if (sink == null) sink = sinks.get(processorName);
        }
        if (sink == null || sink.dataFlow != dataFlow || !sink.processorName.equals(processorName)) {
            throw new IllegalArgumentException("capture does not belong to this processor instance");
        }
        handle.bind(sink);
        if (!sink.beginRecording(handle.queue(), counters)) {
            throw new IllegalStateException("audit capture of '" + processorName + "' is already recording");
        }
        liveSinkMutation.run();
        log.info("audit-capture STARTED for processor '" + sink.selector() + "' at " + sink.path());
    }

    @Override
    public void uninstallCapture(Object resource, String processorName, DataFlow dataFlow) {
        CaptureHandle handle = (CaptureHandle) resource;
        if (handle.sink() == null) return; // never installed
        // endRecording stops recording before it restores the listener, and logs a failed restore rather than throw:
        // a release may therefore close the queue right after an uninstall, whatever the restore did (final review, 10)
        if (handle.sink().endRecordingOf(handle.queue(), dataFlow, dataFlow == null)) {
            liveSinkMutation.run();
            log.info("audit-capture STOPPED for processor '" + processorName + "'");
        }
    }

    @Override
    public void closeCapture(Object resource) {
        CaptureHandle handle = (CaptureHandle) resource;
        if (openResources.remove(handle)) handle.queue().close();
    }

    @Override
    public void processorReleased(AuditCaptureIdentity identity, DataFlow flow) {
        ProcessorSink sink = ownedSinks.get(identity);
        if (sink != null && sink.dataFlow == flow) {
            sink.forget(flow);
            if (ownedSinks.remove(identity, sink)) rememberRetired(sink);
        }
        forgetLegacyBinding(identity.name(), flow);
        sink = sinks.get(identity.name());
        if (sink != null && sink.dataFlow == flow) {
            sink.forget(flow);
            if (sinks.remove(identity.name(), sink)) rememberRetired(sink);
        }
        liveSinkMutation.run();
    }

    private void forgetLegacyBinding(String name, DataFlow flow) {
        ProcessorSink sink;
        synchronized (legacyAttachments) { sink = legacyAttachments.get(flow); }
        if (sink == null || !sink.processorName.equals(name)) return;
        sink.forget(flow);
        synchronized (legacyAttachments) {
            if (legacyAttachments.remove(flow, sink)) rememberRetired(sink);
        }
    }

    private void forgetRouted(String group, String name, DataFlow flow) {
        for (var entry : ownedSinks.entrySet()) {
            AuditCaptureIdentity owner = entry.getKey();
            ProcessorSink sink = entry.getValue();
            if ((group == null || owner.group().equals(group)) && owner.name().equals(name) && sink.dataFlow == flow) {
                if (router == null) {
                    ChronicleQueue queue = sink.applyOnAgent("detach", sink::endRecording);
                    if (queue != null) {
                        CaptureHandle handle = openResources.stream().filter(h -> h.queue() == queue).findFirst().orElse(null);
                        if (handle == null) queue.close(); else closeCapture(handle);
                    }
                }
                sink.forget(flow);
                if (ownedSinks.remove(owner, sink)) rememberRetired(sink);
            }
        }
        forgetLegacyBinding(name, flow);
        liveSinkMutation.run();
    }

    @Override
    public void detach(String group, String name, DataFlow flow) {
        forgetRouted(group, name, flow);
        if (router == null) detach(name, flow);
    }

    @Override
    public void processorStopped(String group, String name, DataFlow flow) {
        forgetRouted(group, name, flow);
        if (router == null) processorStopped(name, flow);
    }

    @Override
    public void detach(String processorName, DataFlow dataFlow) {
        if (router != null) { forgetRouted(null, processorName, dataFlow); return; }
        ProcessorSink sink = sinks.get(processorName);
        if (sink == null || sink.dataFlow != dataFlow) {
            return;
        }
        // on the agent thread, so the restore and the removal are one step there; inline when called on it, as the
        // server does (processorDropped)
        ChronicleQueue released = sink.applyOnAgent("detach", () -> {
            if (sink.dataFlow != dataFlow) return null;
            ChronicleQueue q = sink.endRecording();
            sinks.remove(processorName, sink);
            return q;
        });
        if (released != null) released.close();
        liveSinkMutation.run();
    }

    /**
     * The processor left its group normally (stopped and torn down). Nothing is installed on that instance again:
     * {@code start} is refused until a processor is added under the name, and {@code stop} closes the capture without
     * touching it. A capture still recording carries on into a processor re-added under the name (MA-5.4).
     */
    @Override
    public void processorStopped(String processorName, DataFlow dataFlow) {
        if (router != null) { forgetRouted(null, processorName, dataFlow); return; }
        ProcessorSink sink = sinks.get(processorName);
        if (sink == null || sink.dataFlow != dataFlow) return;
        sink.forget(dataFlow);
        ChronicleQueue released = sink.endRecordingIfGone();
        if (released != null) {
            released.close();
            liveSinkMutation.run();
        }
    }

    @Override
    public boolean isRecording(String processorName) {
        ProcessorSink sink = selectedSink(processorName);
        return sink != null && sink.isRecording();
    }

    /**
     * Snapshot every currently-recording processor as an
     * {@link AuditSinkHandle}. Used by the introspection service.
     */
    public java.util.Map<String, AuditSinkHandle> liveSinks() {
        java.util.Map<String, AuditSinkHandle> out = new java.util.HashMap<>();
        for (ProcessorSink s : attachedSinks()) {
            if (s.isRecording()) {
                AuditSinkHandle handle = s.handle();
                String key = s.identity == null && router != null ? s.selector() + "@" + handle.id() : s.selector();
                out.put(key, handle);
            }
        }
        return out;
    }

    /**
     * Stop every live capture, each as {@link #stop} would. One that cannot be stopped is logged and the rest are
     * still stopped. The server calls it at the end of {@code stop()}, once its processors have stopped and their
     * groups have closed: no processor instance is touched.
     */
    @Override
    public void stopAll() {
        if (router != null) {
            // routed: every group closed its captures as it closed; close any resource a closer had not reached
            for (CaptureHandle handle : openResources.toArray(new CaptureHandle[0])) {
                try {
                    if (handle.sink() != null) {
                        handle.sink().endRecordingOf(handle.queue(), null, true);
                    }
                    closeCapture(handle);
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "audit-capture could not close '" + handle.queue().fileAbsolutePath() + "'", e);
                }
            }
            return;
        }
        for (ProcessorSink sink : attachedSinks()) {
            String name = sink.selector();
            try {
                // at server stop the processors have stopped and their groups have closed: forget each instance, so
                // the capture is ended without touching it and without waiting on a group thread that has gone
                DataFlow flow = sink.dataFlow;
                if (flow != null) sink.forget(flow);
                stop(name);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "audit-capture could not stop '" + name + "'", e);
            }
        }
    }

    /** How long a caller waits for its change to be applied on the processor's agent thread. */
    static final long APPLY_TIMEOUT_SECONDS = 5;

    /** Wait for a sink change the processor's agent thread applies; refused by name rather than hanging. */
    private static void awaitOnAgent(com.telamin.mongoose.dutycycle.AgentHandoff applied, String processorName, String what) {
        try {
            if (!applied.await(APPLY_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                // cancelled unrun: it never runs later, so closing what it would have used is safe. Work the agent
                // thread had already started is waited for instead, even through an interrupt (review of 90f0d9b, 5)
                throw new IllegalStateException("audit " + what + " for '" + processorName + "' was "
                        + (Thread.currentThread().isInterrupted() ? "interrupted"
                        : "not applied on its agent thread within " + APPLY_TIMEOUT_SECONDS + "s")
                        + " before it started; it was cancelled, and nothing changed");
            }
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalStateException || cause instanceof IllegalArgumentException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("audit " + what + " for '" + processorName + "' failed on its agent thread", cause);
        }
    }

    /** Per-processor state holder — keeps the wiring local. */
    private static final class ProcessorSink {
        private final String processorName;
        private final AuditCaptureIdentity identity;
        private final boolean uniquePath;
        /** The live processor; null once it has left its group ({@link #forget}). */
        private volatile DataFlow dataFlow;
        /** mongoose#46: the processor's agent thread; the processor is only driven from it. */
        private volatile java.util.concurrent.Executor onAgentThread = Runnable::run;
        /** Set and cleared on the processor's agent thread, together with the listener it installs. */
        private volatile ChronicleQueue queue;
        private volatile ExcerptAppender appender;
        /** The listener the server configured, taken at attach. Restored on stop; fanned to while recording. */
        private LogRecordListener configuredListener;
        /** Counted, never swallowed: a destination that fails is a finding, not silence. */
        private long captureFailures;
        private long delegateFailures;
        private volatile LogRecordListener previousListener;
        private volatile LogRecordListener captureListener;
        private MongooseCounter recordCounter;
        private volatile Instant startedAt;
        private volatile Instant lastWriteAt;
        private Path lastCapturePath;
        private volatile int startCycle;
        private final java.util.concurrent.atomic.AtomicLong recordCount = new java.util.concurrent.atomic.AtomicLong();

        ProcessorSink(DataFlow dataFlow, String processorName) {
            this(dataFlow, processorName, null, false);
        }

        ProcessorSink(DataFlow dataFlow, String processorName, AuditCaptureIdentity identity, boolean uniquePath) {
            this.dataFlow = dataFlow;
            this.processorName = processorName;
            this.identity = identity;
            this.uniquePath = uniquePath;
        }

        String selector() { return identity == null ? processorName : identity.selector(); }

        boolean isRecording() {
            return queue != null;
        }

        boolean isAttached() {
            return dataFlow != null;
        }

        void requireAttached(String what) {
            if (dataFlow == null) {
                throw new IllegalArgumentException("audit " + what + " for '" + processorName
                        + "' refused: the processor has left its group");
            }
        }

        Path path() {
            ChronicleQueue q = queue;
            return q == null ? null : Path.of(q.fileAbsolutePath());
        }
        AuditSinkHandle handle() {
            Path p = path();
            long size = 0;
            try {
                if (p != null && Files.exists(p)) {
                    // Chronicle stores a directory of cycle files; sum sizes.
                    if (Files.isDirectory(p)) {
                        try (var s = Files.walk(p)) {
                            size = s.filter(Files::isRegularFile)
                                    .mapToLong(f -> f.toFile().length())
                                    .sum();
                        }
                    } else {
                        size = Files.size(p);
                    }
                }
            } catch (IOException e) {
                // Best-effort sizing; UI tolerates -1 from the spec.
                size = -1;
            }
            return new AuditSinkHandle(
                    p != null ? p.getFileName().toString() : processorName,
                    selector(),
                    p,
                    queue == null ? -1 : startCycle,
                    size,
                    recordCount.get(),
                    startedAt,
                    lastWriteAt == null ? startedAt : lastWriteAt,
                    true);
        }

        /**
         * Run {@code change} on the processor's agent thread and wait for it, bounded; its result is returned once it
         * has been applied. Inline when called on that thread, or before its group has started. No lock is held while
         * waiting.
         */
        <T> T applyOnAgent(String what, java.util.function.Supplier<T> change) {
            Object[] result = new Object[1];
            com.telamin.mongoose.dutycycle.AgentHandoff applied;
            try {
                applied = com.telamin.mongoose.dutycycle.AgentHandoff.submit(onAgentThread, () -> result[0] = change.get());
            } catch (RuntimeException refused) {          // the group's hand-off queue stayed full, or the group closed
                throw new IllegalStateException("audit " + what + " for '" + processorName + "' refused: "
                        + refused.getMessage() + "; nothing changed", refused);
            }
            awaitOnAgent(applied, processorName, what);
            @SuppressWarnings("unchecked") T t = (T) result[0];
            return t;
        }

        /** Opened on the caller's thread: the file I/O stays off the processor's thread. */
        ChronicleQueue openQueue(AuditCaptureConfig cfg, java.util.function.Function<Path, ChronicleQueue> builder) {
            Path procDir = null;
            boolean createdDirectory = false;
            try {
                Path root = Path.of(cfg.getDirectory());
                Files.createDirectories(root);
                if (uniquePath) {
                    procDir = Files.createTempDirectory(root, "capture-");
                    createdDirectory = true;
                } else {
                    procDir = root.resolve(processorName);
                    Files.createDirectories(procDir.getParent());
                    try {
                        Files.createDirectory(procDir);
                        createdDirectory = true;
                    } catch (java.nio.file.FileAlreadyExistsException existing) {
                        if (!Files.isDirectory(procDir)) throw existing;
                    }
                    if (Files.exists(procDir.resolve(".mongoose-capture.properties"))) {
                        throw new IllegalArgumentException("standalone capture cannot reuse a routed capture directory: " + procDir);
                    }
                }
                if (uniquePath) {
                    java.util.Properties metadata = new java.util.Properties();
                    metadata.setProperty("schema", "1");
                    metadata.setProperty("processor", selector());
                    if (identity != null) {
                        metadata.setProperty("group", identity.group());
                        metadata.setProperty("name", identity.name());
                        metadata.setProperty("generation", Long.toString(identity.generation()));
                    }
                    try (var writer = Files.newBufferedWriter(procDir.resolve(".mongoose-capture.properties"))) {
                        metadata.store(writer, "Mongoose capture owner");
                    }
                }
                return java.util.Objects.requireNonNull(builder.apply(procDir), "capture builder returned null");
            } catch (IOException failed) {
                RuntimeException failure = new RuntimeException("could not open audit capture under " + cfg.getDirectory(), failed);
                if (createdDirectory) removeFailedOpenDirectory(procDir, failure);
                throw failure;
            } catch (RuntimeException | Error failed) {
                if (createdDirectory) removeFailedOpenDirectory(procDir, failed);
                throw failed;
            }
        }

        /** Only an exclusively created directory is ours to remove; never erase an existing recording. */
        private static void removeFailedOpenDirectory(Path directory, Throwable failure) {
            try (var entries = Files.walk(directory)) {
                for (Path entry : entries.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
            } catch (IOException | RuntimeException cleanupFailed) {
                failure.addSuppressed(cleanupFailed);
            }
        }

        /**
         * On the agent thread: adopt {@code opened} and install the capture listener. False, adopting nothing, when
         * already recording (another start was applied first).
         *
         * <p>Compose the capture listener IN FRONT of the listener the server configured, handed to us at attach
         * because DataFlow has no getter for it. Capture used to REPLACE it: records stopped reaching the console the
         * moment capture started (MA-5a), and stopRecording installed a no-op, so stopping capture sent audit nowhere
         * at all until restart (MA-5b).
         */
        synchronized boolean beginRecording(ChronicleQueue opened, MongooseCountersService counters) {
            if (queue != null) {
                return false;
            }
            requireAttached("start");
            DataFlow flow = dataFlow;
            ExcerptAppender a = opened.createAppender();
            this.appender = a;
            this.startCycle = a.cycle();
            this.recordCounter = counters.counter("audit." + selector() + ".records");
            this.startedAt = Instant.now();
            Path openedPath = Path.of(opened.fileAbsolutePath());
            if (!openedPath.equals(lastCapturePath)) { recordCount.set(0); lastWriteAt = null; }
            lastCapturePath = openedPath;
            this.previousListener = configuredListener;
            this.captureListener = this::onRecordFanOut;
            this.queue = opened;                        // recording from here: the listener is being installed
            try {
                flow.setAuditLogProcessor(captureListener);
            } catch (RuntimeException | Error failed) {
                this.queue = null;
                this.appender = null;
                this.captureListener = null;
                throw failed;
            }
            return true;
        }

        /**
         * On the agent thread (or, for a processor that has left its group, on any): restore the listener the server
         * configured, so audit keeps flowing after capture stops (a no-op here is what made stopping capture discard
         * audit entirely, MA-5b), and release the queue, which the caller closes. Null when not recording.
         */
        synchronized ChronicleQueue endRecording() {
            ChronicleQueue q = queue;
            if (q == null) {
                return null;
            }
            // stop recording first, so a restore that throws can never leave the sink half-recording into a queue that is
            // about to close (review of PR 5, finding 2): the capture listener, if it stays installed, writes nothing
            this.appender = null;
            this.captureListener = null;
            this.queue = null;
            DataFlow flow = dataFlow;
            if (flow != null) {
                try {
                    flow.setAuditLogProcessor(previousListener != null ? previousListener : NoOpLogRecordListener.INSTANCE);
                } catch (RuntimeException failed) {
                    log.log(Level.WARNING, "audit capture of '" + processorName + "' stopped, but the configured listener"
                            + " could not be restored", failed);
                }
            }
            return q;
        }

        /** As {@link #endRecording}, only while the processor has left its group: then no DataFlow is touched. */
        synchronized ChronicleQueue endRecordingIfGone() {
            return dataFlow == null ? endRecording() : null;
        }

        /**
         * Point the sink at a freshly attached {@code DataFlow}; if already recording, install the capture listener
         * on it (MA-5.4). Without this a re-registered processor discards silently: {@code start} returns early
         * because {@link #isRecording()} is true, so nothing ever installs the capture listener on the new instance.
         * Called by the server on the agent thread, at attach, so the install runs inline.
         */
        synchronized void reattach(DataFlow flow, LogRecordListener configured, java.util.concurrent.Executor agent) {
            this.dataFlow = flow;
            this.onAgentThread = agent;
            if (configured != null) this.configuredListener = configured;
        }

        void reinstallCapture() {
            DataFlow flow = dataFlow;
            java.util.concurrent.Executor agent = onAgentThread;
            LogRecordListener capture = captureListener;
            if (capture != null && flow != null) {
                com.telamin.mongoose.dutycycle.AgentHandoff.submit(agent, () -> {
                    if (dataFlow == flow && captureListener == capture) flow.setAuditLogProcessor(capture);
                });
            }
        }

        /** Routed uninstall: end recording only if it is {@code q} that records (never another capture). */
        synchronized boolean endRecordingOf(ChronicleQueue q, DataFlow expected, boolean gone) {
            if (queue != q) return false; // an older session cannot forget or restore this session's instance
            if (expected != null && dataFlow != expected) {
                throw new IllegalArgumentException("capture does not belong to this processor instance");
            }
            if (gone) dataFlow = null;
            endRecording();
            return true;
        }

        /** {@code flow} left its group: nothing is installed on it again. Ignored for any other instance. */
        synchronized void forget(DataFlow flow) {
            if (dataFlow == flow) dataFlow = null;
        }


        /**
         * Fan one record out to BOTH destinations, isolated in both directions.
         *
         * <p>A failure in either must not stop the other receiving the record, and must not be
         * swallowed: it is counted and logged. Silent isolation would reproduce the very defect this
         * fixes, one layer down — and a write that fails while the record is still counted is what lets
         * a later marker read {@code missing_records} rather than claiming a record that never landed.
         */
        void onRecordFanOut(LogRecord record) {
            try {
                onRecord(record);
            } catch (Throwable t) {
                captureFailures++;
                log.log(Level.WARNING, "audit capture write failed for '" + processorName
                        + "' (" + captureFailures + " so far); the record is counted as received", t);
            }
            LogRecordListener delegate = previousListener;
            if (delegate != null) {
                try {
                    delegate.processLogRecord(record);
                } catch (Throwable t) {
                    delegateFailures++;
                    log.log(Level.WARNING, "configured audit listener failed for '" + processorName
                            + "' (" + delegateFailures + " so far)", t);
                }
            }
        }

        void onRecord(LogRecord record) {
            ExcerptAppender a = appender;
            if (a == null) {
                return; // raced with stop()
            }
            CharSequence yaml = record.asCharSequence();
            try (DocumentContext ctx = a.writingDocument()) {
                ctx.wire().getValueOut().text(yaml);
            }
            recordCount.incrementAndGet();
            lastWriteAt = Instant.now();
            if (recordCounter != null) {
                recordCounter.increment();
            }
        }
    }

    private enum NoOpLogRecordListener implements LogRecordListener {
        INSTANCE;

        @Override
        public void processLogRecord(LogRecord record) {
            // intentionally empty
        }
    }
}
