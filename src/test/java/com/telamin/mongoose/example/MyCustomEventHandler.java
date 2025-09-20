/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;

import java.util.List;
import java.util.function.Consumer;

public class MyCustomEventHandler extends ObjectEventHandlerNode {
    @Override
    protected boolean handleEvent(Object event) {
        System.out.println("MyProcessor received event " + event);
        return super.handleEvent(event);
    }

    @ServiceRegistered
    public void registerAdmin(AdminCommandRegistry adminCommandRegistry, String name) {
        System.out.println("MyProcessor registered admin " + name);
        adminCommandRegistry.registerCommand("test", this::test);
        adminCommandRegistry.registerCommand("test", this::test2);
    }

    private void test2(List<String> strings, Consumer<Object> objectConsumer, Consumer<Object> objectConsumer1) {
        System.out.println("MyProcessor test2");
    }

    private void test(List<String> strings, Consumer<Object> objectConsumer, Consumer<Object> objectConsumer1) {
        System.out.println("MyProcessor test");
    }
}
