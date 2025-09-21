/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.service.Service;

import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Simple reflection-based dependency injector for server-level services.
 * <p>
 * It scans an object for methods annotated with {@link ServiceRegistered} and attempts
 * to invoke them using the supplied registered services. The supported method shapes are:
 * - (ServiceInterface)
 * - (ServiceInterface, String name)
 * <p>
 * This injector is intended for non-processor objects (plain services managed by MongooseServer
 * or agent-hosted services). Injection inside DataFlow graphs is already handled by
 * the Fluxtion runtime via ServiceRegistryNode.
 */
public final class ServiceInjector {
    private static final Logger LOG = Logger.getLogger(ServiceInjector.class.getName());

    private ServiceInjector() {
    }

    public static void inject(Object target, Collection<Service<?>> services) {
        if (target == null || services == null || services.isEmpty()) {
            return;
        }
        Class<?> targetClass = target.getClass();
        Method[] methods;
        try {
            methods = targetClass.getMethods();
        } catch (Throwable t) {
            LOG.log(Level.FINE, "ServiceInjector: unable to introspect methods for {0}: {1}", new Object[]{targetClass, t});
            return;
        }

        // Precompute a multimap of service interface -> list of Service entries
        Map<Class<?>, List<Service<?>>> byType = new HashMap<>();
        for (Service<?> svc : services) {
            Class<?> iface = svc.serviceClass();
            byType.computeIfAbsent(iface, k -> new ArrayList<>()).add(svc);
        }

        for (Method m : methods) {
            if (!m.isAnnotationPresent(ServiceRegistered.class)) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1) {
                Class<?> type = params[0];
                injectSingle(target, m, type, byType);
            } else if (params.length == 2 && params[1] == String.class) {
                Class<?> type = params[0];
                injectPair(target, m, type, byType);
            } else {
                LOG.log(Level.FINE, "ServiceInjector: unsupported @ServiceRegistered method signature {0} on {1}", new Object[]{m, targetClass});
            }
        }
    }

    private static void injectSingle(Object target, Method m, Class<?> type, Map<Class<?>, List<Service<?>>> byType) {
        List<Service<?>> matches = findAssignable(byType, type);
        for (Service<?> svc : matches) {
            try {
                m.invoke(target, svc.instance());
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "ServiceInjector: error invoking method {0} with service {1}: {2}", new Object[]{m, svc, t});
            }
        }
    }

    private static void injectPair(Object target, Method m, Class<?> type, Map<Class<?>, List<Service<?>>> byType) {
        List<Service<?>> matches = findAssignable(byType, type);
        for (Service<?> svc : matches) {
            try {
                m.invoke(target, svc.instance(), svc.serviceName());
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "ServiceInjector: error invoking method {0} with service {1}: {2}", new Object[]{m, svc, t});
            }
        }
    }

    private static List<Service<?>> findAssignable(Map<Class<?>, List<Service<?>>> byType, Class<?> paramType) {
        List<Service<?>> result = new ArrayList<>();
        // exact match first
        List<Service<?>> exact = byType.get(paramType);
        if (exact != null) {
            result.addAll(exact);
        }
        // then any service where declared interface is assignable to paramType
        for (Map.Entry<Class<?>, List<Service<?>>> e : byType.entrySet()) {
            Class<?> iface = e.getKey();
            if (paramType.isAssignableFrom(iface) && iface != paramType) {
                result.addAll(e.getValue());
            }
        }
        return result;
    }
}
