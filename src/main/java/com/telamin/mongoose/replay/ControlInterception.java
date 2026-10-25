/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Control requests made by a recorded or replayed processor (design "Deterministic replay"): the processor is given a
 * {@link MongooseServerController} through which every request that changes the server (stop or start a service or a
 * processor, register or remove a service or an event source) is, in RECORD, written to the processor's stream with
 * its outcome and then executed; in REPLAY, never executed, but matched against the recorded request and answered with
 * the recorded outcome. Reads (the registries) go to the server either way.
 */
@Experimental
public final class ControlInterception {

    static final Set<String> CHANGES = Set.of("stopService", "startService", "stopProcessor", "registerService",
            "registerEventSource", "removeService", "addEventProcessor");

    /**
     * Requests that change the service registry: in a replay they are matched and then executed, so the registry the
     * stream's later entries name (a {@code ServiceChanged}) exists; delivery to replayed processors stays suppressed
     * and is replayed in position (final review, finding 2). Every other change is never executed in a replay.
     */
    static final Set<String> REGISTRY = Set.of("registerService", "registerEventSource", "removeService");

    /** What a wrapper does with a request that changes the server. */
    interface Handler {
        Object handle(String method, List<String> args, Method m, Object[] rawArgs) throws Throwable;
    }

    private ControlInterception() {
    }

    /** The controller service {@code service} wrapped so that requests changing the server go to {@code handler}. */
    static Service<?> wrap(Service<?> service, Handler handler) {
        MongooseServerController target = (MongooseServerController) service.instance();
        Object proxy = Proxy.newProxyInstance(MongooseServerController.class.getClassLoader(),
                new Class<?>[]{MongooseServerController.class}, (p, m, a) -> {
                    if (CHANGES.contains(m.getName())) return handler.handle(m.getName(), describe(a), m, a);
                    if (m.getDeclaringClass() == Object.class) {
                        return switch (m.getName()) {
                            case "hashCode" -> System.identityHashCode(p);
                            case "equals" -> p == a[0];
                            default -> "intercepted " + target;
                        };
                    }
                    return invoke(m, target, a);
                });
        @SuppressWarnings({"unchecked", "rawtypes"})
        Service<?> wrapped = new Service(proxy, service.serviceClass(), service.serviceName());
        return wrapped;
    }

    static Object invoke(Method m, Object target, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Arguments as stable text: a service or a source by its name, never by identity. */
    static List<String> describe(Object[] args) {
        List<String> out = new ArrayList<>();
        if (args == null) return out;
        for (Object a : args) {
            // a supplier or an idle strategy has no stable text: it is named by its kind only
            if (a instanceof java.util.function.Supplier<?>) out.add("supplier");
            else if (a instanceof org.agrona.concurrent.IdleStrategy) out.add("idleStrategy");
            else if (a instanceof Service<?> s) out.add("service:" + s.serviceName());
            else if (a instanceof com.telamin.mongoose.service.EventSource<?> src) out.add("source:" + src.getClass().getName());
            else out.add(String.valueOf(a));
        }
        return out;
    }

    static boolean isController(Service<?> service) {
        return service.instance() instanceof MongooseServerController;
    }
}
