/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 */

package com.telamin.mongoose.service.admin.impl;

import com.fluxtion.runtime.annotations.feature.Experimental;
import com.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import lombok.extern.java.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Experimental
@Log
public class CliAdminCommandProcessor implements Lifecycle {

    private static final AtomicBoolean runLoop = new AtomicBoolean(true);
    private ExecutorService executorService;
    private AdminCommandRegistry adminCommandRegistry;

    @Override
    public void init() {
        log.info("init");
        executorService = Executors.newSingleThreadExecutor();
    }

    @ServiceRegistered
    public void adminRegistry(AdminCommandRegistry adminCommandRegistry, String name) {
        log.info("AdminCommandRegistry available name: " + name);
        this.adminCommandRegistry = adminCommandRegistry;
    }

    @Override
    public void start() {
        log.info("start");
        executorService.submit(() -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                //do nothing
            }
            log.info("starting");

            Scanner scanner = new Scanner(System.in);
            while (runLoop.get()) {
                // Prompt the user
                System.out.print("Command > ");

                // Read user input as String
                String[] commandArgs = scanner.nextLine().trim().split(" ");

                if (commandArgs.length > 0) {
                    AdminCommandRequest adminCommandRequest = new AdminCommandRequest();
                    List<String> commandArgsList = new ArrayList<>(Arrays.asList(commandArgs));
                    commandArgsList.remove(0);

                    adminCommandRequest.setCommand(commandArgs[0]);
                    adminCommandRequest.setArguments(commandArgsList);
                    adminCommandRequest.setOutput(System.out::println);
                    adminCommandRequest.setErrOutput(System.err::println);

                    log.info("adminCommandRequest: " + adminCommandRequest);
                    if (adminCommandRegistry != null) {
                        adminCommandRegistry.processAdminCommandRequest(adminCommandRequest);
                    }
                }
            }
        });
    }


    @Override
    public void stop() {
        log.info("stop");
        runLoop.set(false);
    }

    @Override
    public void tearDown() {
        log.info("stop");
        runLoop.set(false);
        executorService.shutdown();
    }
}

