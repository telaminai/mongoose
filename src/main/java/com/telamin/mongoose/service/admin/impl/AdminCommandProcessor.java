/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.admin.impl;

import com.fluxtion.runtime.StaticEventProcessor;
import com.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.*;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.AdminFunction;
import lombok.extern.java.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Processor that registers and dispatches admin commands through the event flow.
 * It registers built-in commands and allows services to register additional commands
 * that can be executed either directly or via event queues.
 */
@Experimental
@Log
public class AdminCommandProcessor implements AdminCommandRegistry, LifeCycleEventSource<AdminCommand> {

    /**
     * Create a new AdminCommandProcessor.
     */
    public AdminCommandProcessor() {
    }

    private final Map<String, AdminCommand> registeredCommandMap = new HashMap<>();
    private EventFlowManager eventFlowManager;

    private static final String HELP_MESSAGE = """
            default commands:
            ---------------------------
            quit         - exit the console
            help/?       - this message
            commands     - registered service commands
            eventSources - list event sources
            """;

    @Override
    public void init() {
        log.info("init");
    }

    @Override
    public void setEventFlowManager(EventFlowManager eventFlowManager, String serviceName) {
        this.eventFlowManager = eventFlowManager;
        eventFlowManager.registerEventMapperFactory(AdminCommandInvoker::new, AdminCallbackType.class);
    }

    @Override
    public void start() {
        log.info("start");
        registerCommand("help", this::printHelp);
        registerCommand("?", this::printHelp);
        registerCommand("eventSources", this::printQueues);
        registerCommand("commands", this::registeredCommands);
    }

    @Override
    public void processAdminCommandRequest(AdminCommandRequest command) {
        String commandName = command.getCommand().trim();
        log.info("processing: " + command + " name: '" + commandName + "'");
        AdminCommand adminCommand = registeredCommandMap.get(commandName);
        if (adminCommand != null) {
            adminCommand.publishCommand(command);
        } else {
            log.info("command not found: " + commandName);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <OUT, ERR> void registerCommand(String name, AdminFunction<OUT, ERR> command) {
        if (com.telamin.mongoose.dispatch.ProcessorContext.currentProcessor() == null) {
            registeredCommandMap.put(name, new AdminCommand((AdminFunction<Object, Object>) command));
        } else {
            String queueKey = "adminCommand." + name;
            addCommand(
                    name,
                    queueKey,
                    new AdminCommand((AdminFunction<Object, Object>) command, eventFlowManager.registerEventSource(queueKey, this)));
        }
    }

    @Override
    public List<String> commandList() {
        return registeredCommandMap.keySet().stream().sorted().collect(Collectors.toList());
    }

    @Override
    public void stop() {
        log.info("stop");
    }

    @Override
    public void tearDown() {
        log.info("stop");
    }

    private void printHelp(List<String> args, Consumer<String> out, Consumer<String> err) {
        out.accept(HELP_MESSAGE);
    }

    private void printQueues(List<String> args, Consumer<String> out, Consumer<String> err) {
        StringBuilder sb = new StringBuilder();
        eventFlowManager.appendQueueInformation(sb);
        out.accept(sb.toString());
    }

    private void registeredCommands(List<String> args, Consumer<String> out, Consumer<String> err) {
        String commandsString = registeredCommandMap.keySet().stream()
                .sorted()
                .collect(Collectors.joining(
                        "\n",
                        "Service commands:\n---------------------------\n",
                        "\n"));
        out.accept(commandsString);
    }

    private void addCommand(String name, String queueKey, AdminCommand adminCommand) {
        StaticEventProcessor staticEventProcessor = com.telamin.mongoose.dispatch.ProcessorContext.currentProcessor();
        log.info("registered command:" + name + " queue:" + queueKey + " processor:" + staticEventProcessor);

        registeredCommandMap.put(name, adminCommand);

        EventSubscriptionKey<?> subscriptionKey = new EventSubscriptionKey<>(
                new EventSourceKey<>(queueKey),
                AdminCallbackType.class
        );

        staticEventProcessor.getSubscriptionManager().subscribe(subscriptionKey);
    }

    @Override
    public void subscribe(EventSubscriptionKey<AdminCommand> eventSourceKey) {
    }

    @Override
    public void unSubscribe(EventSubscriptionKey<AdminCommand> eventSourceKey) {
    }

    @Override
    public void setEventToQueuePublisher(com.telamin.mongoose.dispatch.EventToQueuePublisher<AdminCommand> targetQueue) {
    }

    private static class AdminCallbackType implements CallBackType {

        @Override
        public String name() {
            return "AdminCallback";
        }

    }
}

