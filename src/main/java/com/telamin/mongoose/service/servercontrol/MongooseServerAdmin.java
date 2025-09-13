/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.servercontrol;

import com.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
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
    }

    @Override
    public void tearDown() {
        log.info("Fluxtion Server admin tearDown");
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
