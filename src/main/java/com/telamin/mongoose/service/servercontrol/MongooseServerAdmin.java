/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.servercontrol;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.audit.AuditSinkHandle;
import com.telamin.mongoose.service.audit.MongooseAuditCaptureService;
import com.telamin.mongoose.service.audit.MongooseAuditIntrospectionService;
import com.telamin.mongoose.service.counters.MongooseLatencyService;
import lombok.extern.java.Log;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;


/**
 * MongooseServerAdmin is responsible for managing and administering a
 * Fluxtion server. It uses the {@link AdminCommandRegistry} to register
 * administrative commands and the {@link MongooseServerController} to
 * manage registered services and processors on the server.
 * <p>
 * This class implements the {@link Lifecycle} interface, allowing it
 * to initialize, start, and tear down administrative services.
 * <p>
 * Key responsibilities include:
 * - Registering administrative commands that interact with the server's
 * services and event processors.
 * - Providing functionality to list services, start/stop services, list
 * processors, and stop processors.
 * <p>
 * Lifecycle:
 * - `init()`: Prepares the server admin system for operation.
 * - `start()`: Registers administrative commands and starts the server admin.
 * - `tearDown()`: Cleans up resources used by the server admin system.
 * <p>
 * Commands Registered:
 * - `server.service.list`: Lists all registered server services.
 * - `server.processors.list`: Lists all registered server processors.
 * - `server.processors.stop`: Stops a specified event processor.
 * <p>
 * Logging:
 * - Logs are generated at each lifecycle stage and on service/command
 * interactions for tracking and debugging purposes.
 */
@Log
public class MongooseServerAdmin implements Lifecycle {

    private AdminCommandRegistry registry;
    private MongooseServerController serverController;
    private MongooseLatencyService latencyService;
    private MongooseAuditCaptureService auditCapture;
    private MongooseAuditIntrospectionService auditIntrospection;
    private final java.util.concurrent.atomic.AtomicReference<ControlExportJob> controlExport =
            new java.util.concurrent.atomic.AtomicReference<>();

    private static final class ControlExportJob {
        final String id = java.util.UUID.randomUUID().toString();
        final String group;
        final java.nio.file.Path directory;
        volatile String result = "PENDING";
        volatile boolean finished;

        ControlExportJob(String group, java.nio.file.Path directory) {
            this.group = group;
            this.directory = directory;
        }
    }

    @ServiceRegistered
    public void admin(AdminCommandRegistry registry) {
        this.registry = registry;
        log.info("Admin command registry");
    }

    @ServiceRegistered
    public void server(MongooseServerController serverController) {
        this.serverController = serverController;
        log.info("Server command registry");
    }

    @ServiceRegistered
    public void latencyService(MongooseLatencyService latencyService) {
        this.latencyService = latencyService;
        log.info("Latency service available for admin toggle: " + latencyService);
    }

    @ServiceRegistered
    public void auditCaptureService(MongooseAuditCaptureService svc) {
        this.auditCapture = svc;
        log.info("Audit capture service available for admin commands: " + svc);
    }

    @ServiceRegistered
    public void auditIntrospectionService(MongooseAuditIntrospectionService svc) {
        this.auditIntrospection = svc;
        log.info("Audit introspection service available for admin commands: " + svc);
    }

    @Override
    public void init() {
        log.info("Fluxtion Server admin init");
    }

    @Override
    public void start() {
        log.info("Fluxtion Server admin started");
        registry.registerCommand("server.service.list", this::listServices);
//        registry.registerCommand("server.service.start", this::startServices);
//        registry.registerCommand("server.service.stop", this::stopServices);

        registry.registerCommand("server.processors.list", this::listProcessors);
        registry.registerCommand("server.processors.stop", this::stopProcessors);

        // Latency capture toggle. Exposed on the admin command surface so
        // ops can flip it from CLI, and consumed by the per-node stats
        // tab in svc-admin-web. No-op (with a clear message) when no
        // MongooseLatencyService is bound (latencyHistograms: false).
        registry.registerCommand("latency.status", this::latencyStatus);
        registry.registerCommand("latency.enable", this::latencyEnable);
        registry.registerCommand("latency.disable", this::latencyDisable);
        registry.registerCommand("latency.toggle", this::latencyToggle);
        registry.registerCommand("latency.reset", this::latencyReset);

        // Audit-log capture commands (Phase 2 of the audit-log-viewer
        // plugin). Mirror the latency.* shape — every command checks
        // the service is installed and emits a clear message when not.
        registry.registerCommand("audit.start", this::auditStart);
        registry.registerCommand("audit.stop", this::auditStop);
        registry.registerCommand("audit.status", this::auditStatus);
        registry.registerCommand("audit.list", this::auditList);
        registry.registerCommand("control.audit.export", this::controlAuditExport);
        registry.registerCommand("control.audit.export.status", this::controlAuditExportStatus);
        registry.registerCommand("control.group.status", this::controlGroupStatus);
        registry.registerCommand("control.audit.acknowledge", this::acknowledgeControlAudit);
        registry.registerCommand("control.capture.acknowledge", this::acknowledgeCaptureFailures);
        // #79: overload health per feed and queue, and the operator clear of a latched never-drop feed
        registry.registerCommand("overload.status", this::overloadStatus);
        registry.registerCommand("overload.clear", this::overloadClear);
    }

    private void acknowledgeCaptureFailures(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() != 2) {
            err.accept("usage: control.capture.acknowledge <group>");
            return;
        }
        if (!(serverController instanceof com.telamin.mongoose.MongooseServer server)) {
            err.accept("control capture acknowledge requires a MongooseServer");
            return;
        }
        try {
            server.acknowledgeCaptureFailures(args.get(1));
            out.accept("capture failures acknowledged for group " + args.get(1) + "; they stay listed and owned");
        } catch (IllegalArgumentException failed) {
            err.accept(failed.getMessage());
        }
    }

    private void acknowledgeControlAudit(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() != 2) {
            err.accept("usage: control.audit.acknowledge <group>");
            return;
        }
        if (!(serverController instanceof com.telamin.mongoose.MongooseServer server)) {
            err.accept("control audit acknowledge requires a MongooseServer");
            return;
        }
        try {
            server.acknowledgeControlAuditGap(args.get(1));
            out.accept("control audit gap acknowledged for group " + args.get(1) + "; the evidence stays incomplete");
        } catch (IllegalArgumentException failed) {
            err.accept(failed.getMessage());
        }
    }

    private void controlGroupStatus(List<String> args, Consumer<String> out, Consumer<String> err) {
        boolean json = args.size() == 3 && "--json".equals(args.get(2));
        if (args.size() != 2 && !json) {
            err.accept("usage: control.group.status <group> [--json]");
            return;
        }
        if (!(serverController instanceof com.telamin.mongoose.MongooseServer server)) {
            err.accept("control group status requires a MongooseServer");
            return;
        }
        try {
            var status = server.controlGroupStatus(args.get(1));
            out.accept(json ? status.toJson() : status.toString());
        } catch (IllegalArgumentException failed) {
            err.accept(failed.getMessage());
        }
    }

    private void controlAuditExport(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() != 3) {
            err.accept("usage: control.audit.export <group> <new-directory>");
            return;
        }
        if (!(serverController instanceof com.telamin.mongoose.MongooseServer server)) {
            err.accept("control audit export requires a MongooseServer");
            return;
        }
        final ControlExportJob job;
        try {
            job = new ControlExportJob(args.get(1), java.nio.file.Path.of(args.get(2)).toAbsolutePath().normalize());
        } catch (RuntimeException invalid) {
            err.accept("control audit export: " + invalid);
            return;
        }
        ControlExportJob previous = controlExport.get();
        if ((previous != null && !previous.finished) || !controlExport.compareAndSet(previous, job)) {
            err.accept("control audit export already pending; inspect control.audit.export.status");
            return;
        }
        // At most one daemon worker and one retained result per admin instance. Never hold an output callback
        // beyond this command: transports may release their response as soon as it returns.
        try {
            Thread.ofVirtual().name("mongoose-control-audit-export").start(() -> {
                try {
                    var audit = server.exportControlAudit(job.group, job.directory);
                    var verification = com.telamin.mongoose.control.ControlAuditExport.verify(audit);
                    job.result = (verification.completeness() == com.telamin.mongoose.control.ControlAuditExport.Completeness.UNKNOWN
                            ? "FAILED" : "EXPORTED") + " completeness=" + verification.completeness()
                            + " audit=" + audit + " reason=" + verification.reason();
                } catch (Exception failed) {
                    job.result = "FAILED " + failed;
                } finally {
                    if (job.result.equals("PENDING")) job.result = "FAILED export worker terminated abnormally";
                    job.finished = true;
                }
            });
        } catch (Throwable failed) {
            job.result = "FAILED could not start export worker: " + failed;
            job.finished = true;
            throw failed;
        }
        out.accept("control audit export job=" + job.id + " directory=" + job.directory
                + "; inspect control.audit.export.status " + job.id);
    }

    private void controlAuditExportStatus(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() > 2) {
            err.accept("usage: control.audit.export.status [job-id]");
            return;
        }
        ControlExportJob job = controlExport.get();
        if (job == null || (args.size() == 2 && !job.id.equals(args.get(1)))) {
            err.accept("unknown control audit export job (only the latest job is retained)");
            return;
        }
        out.accept("control audit export job=" + job.id + " group=" + job.group + " directory=" + job.directory
                + " " + job.result);
    }

    @Override
    public void tearDown() {
        log.info("Fluxtion Server admin tearDown");
    }

    private void latencyStatus(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (latencyService == null || !latencyService.isOperational()) {
            out.accept("latency: not installed (set performanceMonitoring.latencyHistograms: true in YAML)");
            return;
        }
        out.accept("latency: " + (latencyService.isEnabled() ? "ENABLED" : "DISABLED"));
    }

    private void latencyEnable(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (latencyService == null || !latencyService.isOperational()) {
            err.accept("latency: not installed — set performanceMonitoring.latencyHistograms: true and restart");
            return;
        }
        latencyService.setEnabled(true);
        out.accept("latency: ENABLED");
    }

    private void latencyDisable(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (latencyService == null || !latencyService.isOperational()) {
            err.accept("latency: not installed");
            return;
        }
        latencyService.setEnabled(false);
        out.accept("latency: DISABLED");
    }

    private void latencyToggle(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (latencyService == null || !latencyService.isOperational()) {
            err.accept("latency: not installed");
            return;
        }
        boolean next = !latencyService.isEnabled();
        latencyService.setEnabled(next);
        out.accept("latency: " + (next ? "ENABLED" : "DISABLED"));
    }

    private void latencyReset(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (latencyService == null || !latencyService.isOperational()) {
            err.accept("latency: not installed");
            return;
        }
        latencyService.reset();
        out.accept("latency: histograms reset");
    }

    private boolean auditUnavailable(Consumer<String> err) {
        if (auditCapture == null) {
            err.accept("audit: capture service not installed (set performanceMonitoring.auditCapture.enabled: true and restart)");
            return true;
        }
        return false;
    }

    private void auditStart(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (auditUnavailable(err)) return;
        if (args.size() < 2) {
            err.accept("usage: audit.start <processorName>");
            return;
        }
        String processor = args.get(1);
        // mongoose#46: returns once the capture listener is installed on the processor's own thread; refused, not hung,
        // when it cannot be (processor gone, group not running, its thread not reaching it in time)
        try {
            auditCapture.start(processor);
            out.accept("audit: RECORDING " + processor);
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.accept("audit: " + e.getMessage());
        }
    }

    private void auditStop(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (auditUnavailable(err)) return;
        if (args.size() < 2) {
            err.accept("usage: audit.stop <processorName>");
            return;
        }
        String processor = args.get(1);
        try {
            auditCapture.stop(processor);
            out.accept("audit: STOPPED " + processor);
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.accept("audit: " + e.getMessage());
        }
    }

    private void auditStatus(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (auditUnavailable(err)) return;
        if (args.size() >= 2) {
            String processor = args.get(1);
            try {
                boolean recording = auditCapture.isRecording(processor);
                AuditSinkHandle handle = auditIntrospection == null ? null : recording
                        ? auditIntrospection.currentSink(processor) : auditIntrospection.latestCapture(processor);
                out.accept("audit: " + processor + " " + (recording ? "RECORDING" : "idle")
                        + (handle == null ? "" : (recording ? " " : " latest ") + captureLocation(handle)));
            } catch (IllegalArgumentException | IllegalStateException refused) {
                err.accept("audit: " + refused.getMessage());
            }
            return;
        }
        // No processor — list state for every active processor we know about.
        if (auditIntrospection == null) {
            out.accept("audit: introspection service not bound — cannot list");
            return;
        }
        StringBuilder sb = new StringBuilder("audit status:");
        auditIntrospection.currentSinks().forEach((proc, handle) ->
                sb.append("\n\t").append("RECORDING ").append(captureLocation(handle)));
        out.accept(sb.toString());
    }

    private void auditList(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (auditIntrospection == null) {
            err.accept("audit: introspection service not installed");
            return;
        }
        List<AuditSinkHandle> all = auditIntrospection.listAvailable();
        if (all.isEmpty()) {
            out.accept("audit: no captures available");
            return;
        }
        StringBuilder sb = new StringBuilder("audit files:");
        for (AuditSinkHandle h : all) {
            sb.append("\n\t")
                    .append(h.isLive() ? "● " : "  ")
                    .append(captureLocation(h))
                    .append("  ").append(h.recordCount() < 0 ? "?" : h.recordCount()).append(" records")
                    .append("  ").append(h.sizeBytes() < 0 ? "?" : (h.sizeBytes() + " bytes"));
        }
        out.accept(sb.toString());
    }

    private static String captureLocation(AuditSinkHandle handle) {
        return "id=" + handle.id() + " selector=" + handle.processorName() + " path=" + handle.path();
    }

    private void listServices(List<String> args, Consumer<String> out, Consumer<String> err) {
        out.accept(
                serverController.registeredServices()
                        .entrySet()
                        .stream()
                        .map(e -> e.getKey() + ": " + e.getValue())
                        .collect(Collectors.joining("\n\t", "services:\n\t", "\n")));
    }

    private void stopServices(List<String> args, Consumer<String> out, Consumer<String> err) {
        out.accept("stopping service:" + args.get(1));
        serverController.stopService(args.get(1));
    }

    private void startServices(List<String> args, Consumer<String> out, Consumer<String> err) {
        out.accept("starting service:" + args.get(1));
        serverController.startService(args.get(1));
    }

    private void listProcessors(List<String> args, Consumer<String> out, Consumer<String> err) {
        out.accept(
                serverController.registeredProcessors()
                        .entrySet()
                        .stream()
                        .map(e -> {
                            String groupName = e.getKey();
                            return "group:" + groupName +
                                    "\nprocessors:" + e.getValue().stream()
                                    .map(namedEventProcessor -> groupName + "/" + namedEventProcessor.name() + " -> " + namedEventProcessor.eventProcessor())
                                    .collect(Collectors.joining("\n\t", "\n\t", "\n"));
                        })
                        .collect(Collectors.joining("\n", "\n", "\n")));
    }

    private void overloadStatus(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (!(serverController instanceof com.telamin.mongoose.MongooseServer server)) {
            err.accept("overload.status needs a MongooseServer");
            return;
        }
        StringBuilder sb = new StringBuilder("overload:");
        server.overloadStates().forEach((feed, queues) -> {
            sb.append("\n\t").append(feed);
            queues.forEach((q, s) -> sb.append("\n\t\t").append(q).append(": ").append(s));
        });
        out.accept(sb.toString());
    }

    private void overloadClear(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (!(serverController instanceof com.telamin.mongoose.MongooseServer server)) {
            err.accept("overload.clear needs a MongooseServer");
            return;
        }
        if (args.size() < 2) {
            err.accept("usage: overload.clear <feed>");
            return;
        }
        int cleared = server.clearOverloadState(args.get(1));
        if (cleared < 0) {
            err.accept("no such feed: " + args.get(1));
        } else {
            out.accept("overload.clear " + args.get(1) + ": " + cleared + " queue(s) cleared");
        }
    }

    private void stopProcessors(List<String> args, Consumer<String> out, Consumer<String> err) {
        // exactly one argument, <group>/<processor>, both parts non-blank: anything else is refused before any stop
        String arg = args.size() == 2 ? args.get(1) : null;
        String[] splitArgs = arg == null ? new String[0] : arg.split("/", -1);
        if (splitArgs.length != 2 || splitArgs[0].isBlank() || splitArgs[1].isBlank()) {
            err.accept("stop REFUSED (REFUSED_INVALID): usage: server.processors.stop <group>/<processor>");
            return;
        }
        out.accept("stopping processor:" + arg);
        if (serverController instanceof com.telamin.mongoose.MongooseServer server) {
            // the admin transport runs commands inline: wait at most 1 s, then report pending; the stop still applies.
            // An unknown group or name is answered REFUSED at once, never "pending"
            com.telamin.mongoose.control.ControlReply reply = server.stopProcessor(splitArgs[0], splitArgs[1], 1_000);
            out.accept(reply == null ? "stop pending (still applies): " + arg
                    : "stop " + reply.outcome() + " (" + reply.kind() + "): " + reply.reason());
        } else {
            serverController.stopProcessor(splitArgs[0], splitArgs[1]);
        }
    }
}
