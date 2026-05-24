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
        try {
            auditCapture.start(processor);
            out.accept("audit: RECORDING " + processor);
        } catch (IllegalArgumentException e) {
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
        auditCapture.stop(processor);
        out.accept("audit: STOPPED " + processor);
    }

    private void auditStatus(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (auditUnavailable(err)) return;
        if (args.size() >= 2) {
            String processor = args.get(1);
            out.accept("audit: " + processor + " " + (auditCapture.isRecording(processor) ? "RECORDING" : "idle"));
            return;
        }
        // No processor — list state for every active processor we know about.
        if (auditIntrospection == null) {
            out.accept("audit: introspection service not bound — cannot list");
            return;
        }
        StringBuilder sb = new StringBuilder("audit status:");
        auditIntrospection.currentSinks().forEach((proc, handle) ->
                sb.append("\n\t").append(proc).append("  RECORDING  ").append(handle.path()));
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
                    .append(h.processorName())
                    .append("  ").append(h.recordCount() < 0 ? "?" : h.recordCount()).append(" records")
                    .append("  ").append(h.sizeBytes() < 0 ? "?" : (h.sizeBytes() + " bytes"))
                    .append("  ").append(h.path());
        }
        out.accept(sb.toString());
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

    private void stopProcessors(List<String> args, Consumer<String> out, Consumer<String> err) {
        String arg = args.get(1);
        out.accept("stopping processor:" + arg);
        String[] splitArgs = arg.split("/");
        serverController.stopProcessor(splitArgs[0], splitArgs[1]);
    }
}
