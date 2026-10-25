/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.audit;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;

/**
 * CONTROL surface for the audit-log capture plugin. Starts and stops
 * per-processor audit recording into a persistent backend (Chronicle
 * BinaryWire from Phase 1 onwards; NoOp default).
 *
 * <p>Paired with {@link MongooseAuditIntrospectionService}, the
 * read-only surface that lists available captures and exposes sink
 * metadata. The split keeps callers that only need to LIST audit files
 * from holding a reference to anything that can mutate capture state —
 * mirrors the existing
 * {@code MongooseServerController} (control) /
 * {@code MongooseIntrospectionService} (read) split already in the
 * codebase.
 *
 * <p>Recording is OFF by default for every processor at boot, even when
 * the service is installed (i.e. {@code performanceMonitoring.auditCapture.enabled: true}).
 * The operator opts each processor in via:
 * <ul>
 *   <li>YAML {@code performanceMonitoring.auditCapture.autoStart: [pnl-processor, …]} —
 *       starts at boot;</li>
 *   <li>{@code POST /api/audit/&#123;processor&#125;/start} on svc-admin-web;</li>
 *   <li>{@code audit.start &lt;processor&gt;} admin command;</li>
 *   <li>{@code MongooseAuditCaptureService.start(name)} directly from Java.</li>
 * </ul>
 *
 * <p>When the NoOp impl is installed, every method is a JIT-elided no-op
 * and {@link #isRecording(String)} returns false unconditionally.
 */
public interface MongooseAuditCaptureService {

    String SERVICE_NAME = "com.telamin.mongoose.service.audit.MongooseAuditCaptureService";

    /**
     * Register a processor with the capture service. Called by the
     * mongoose runtime from {@code MongooseServer.addEventProcessor}
     * once per processor, BEFORE any call to {@link #start(String)}.
     * The capture service remembers the {@code DataFlow} reference so
     * {@code start} can install a {@code LogRecordListener} on it
     * later without needing the runtime to pass the DataFlow again.
     *
     * <p>Default implementation is a no-op so the NoOp service ignores
     * the call without overhead.
     *
     * @param dataFlow      the live processor instance.
     * @param processorName the YAML name (as it appears in
     *                      {@code eventHandlers.&lt;name&gt;}).
     */
    default void attach(DataFlow dataFlow, String processorName) {
        // no-op default — NoOp impl ignores
    }

    /**
     * Register a processor, and hand over the listener the server has configured on it.
     *
     * <p><b>Why the listener must arrive here, at attach.</b> Capture installs its own
     * {@code LogRecordListener}, and {@code DataFlow} has no getter for the current one, so a capture
     * service that does not receive it cannot fan out to it and cannot restore it on stop. It used to
     * do neither: records stopped reaching the console the moment capture started, and stopping capture
     * left audit going nowhere at all until the next restart.
     *
     * <p>It must be captured <b>at attach, per processor</b>. The server's field is a
     * {@code private static} that each {@code bootServer} call overwrites, so two servers in one JVM
     * share it — reading it later would restore the wrong listener.
     *
     * <p>Default delegates to {@link #attach(DataFlow, String)} so existing implementations, including
     * the NoOp, keep working unchanged.
     *
     * @param configuredListener the listener the server installed on this processor; may be null.
     */
    default void attach(DataFlow dataFlow, String processorName, LogRecordListener configuredListener) {
        attach(dataFlow, processorName);
    }

    /**
     * As above, with the processor's agent thread (mongoose#46): {@code start}/{@code stop} are called from the admin
     * transport's thread, and change the processor's audit sink through {@code onAgentThread}, so the processor is only
     * ever driven from its own thread. They return once the change has been applied there, and are refused with an
     * {@link IllegalStateException}, changing nothing, when it cannot be applied in time.
     *
     * @deprecated a server routes capture through each processor group's control processor (design "Audit capture"),
     * which installs and uninstalls on the group's thread itself; the executor is used only by a service no server routes
     */
    @Deprecated
    default void attach(DataFlow dataFlow, String processorName, LogRecordListener configuredListener,
                        java.util.concurrent.Executor onAgentThread) {
        attach(dataFlow, processorName, configuredListener);
    }

    /**
     * Forget a processor that left the server without stopping normally (a failed start, mongoose#57): stop any capture
     * of it and drop the reference to {@code dataFlow}. Ignored when {@code processorName} is now bound to another
     * {@code DataFlow}. Default: a no-op.
     */
    default void detach(String processorName, DataFlow dataFlow) {
        // no-op default — NoOp impl ignores
    }

    /**
     * The processor left its group normally: it was stopped and torn down (mongoose#46). Nothing is installed on that
     * instance again: {@link #start} is refused until a processor is added under the name, and {@link #stop} closes
     * the capture without touching it. A capture still recording carries on into a processor re-added under the name.
     * Ignored when {@code processorName} is now bound to another {@code DataFlow}. Default: a no-op.
     */
    default void processorStopped(String processorName, DataFlow dataFlow) {
        // no-op default — NoOp impl ignores
    }

    /**
     * Begin capturing audit records for the named processor. The
     * processor must already be registered with the mongoose runtime
     * (i.e. live in {@code MongooseServer.registeredProcessors()}) AND
     * previously attached via {@link #attach(DataFlow, String)}.
     * Idempotent — calling on an already-recording processor is a no-op
     * and does not roll the underlying sink.
     *
     * @param processorName the YAML processor name (as it appears in
     *                      {@code eventHandlers.&lt;name&gt;}).
     * <p>In a server with capture enabled, the start is a request to the
     * processor's group: the queue is opened on an I/O helper, installed on
     * the processor's agent thread, and this returns once it records.
     * Called on an agent thread, or before the group has started, it
     * returns at once and the start applies later.
     *
     * @throws IllegalArgumentException if no processor with that name
     *         is registered.
     * @throws IllegalStateException if the start was refused or failed (the
     *         group is not running, the processor has not started, the open
     *         failed or timed out, too many opens are in flight), or was not
     *         applied in time; the message says whether anything may still
     *         change.
     */
    void start(String processorName);

    /**
     * Halt capture for the named processor. Flushes any pending records
     * and closes the sink. Idempotent — calling on a non-recording
     * processor is a no-op.
     */
    void stop(String processorName);

    /**
     * Halt every live capture, flushing and closing each sink. The server calls it once its processors have stopped,
     * at the end of {@code stop()}. Default: nothing to stop.
     *
     * @deprecated each processor group closes its captures as it closes (design "Audit capture"); this only closes what
     * a closer had not reached when the server stopped
     */
    @Deprecated
    default void stopAll() {
    }

    /**
     * True iff capture is currently active for the named processor.
     * Constant-time lookup; safe to call from any thread.
     */
    boolean isRecording(String processorName);
}