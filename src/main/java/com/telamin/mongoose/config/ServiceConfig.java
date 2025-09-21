/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.fluxtion.agrona.concurrent.Agent;
import com.fluxtion.agrona.concurrent.IdleStrategy;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.dutycycle.ServiceAgent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;

/**
 * Configuration class for services in the Fluxtion server framework.
 * Provides a flexible way to configure and build service instances with optional
 * agent capabilities. Supports both direct instantiation and builder pattern.
 *
 * @param <T> the type of service being configured
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true, fluent = true)
public class ServiceConfig<T> {

    /**
     * The service instance to be configured
     */
    private T service;
    /**
     * The fully qualified class name of the service
     */
    private String serviceClass;
    /**
     * The name identifier for the service
     */
    private String name;
    /**
     * Optional agent group name for agent-enabled services
     */
    private String agentGroup;
    /**
     * Optional idle strategy for agent-enabled services
     */
    private IdleStrategy idleStrategy;

    public ServiceConfig(T service, Class<T> serviceClass, String name) {
        this(service, serviceClass.getCanonicalName(), name, null, null);
    }

    public boolean isAgent() {
        return agentGroup != null;
    }

    public void setService(T service) {
        this.service = service;
    }

    public void setServiceClass(String serviceClass) {
        this.serviceClass = serviceClass;
    }

    public void setName(String name) {
        this.name = name;
    }

    public T getService() {
        return service;
    }

    public String getServiceClass() {
        return serviceClass;
    }

    public String getName() {
        return name;
    }

    public String getAgentGroup() {
        return agentGroup;
    }

    public void setAgentGroup(String agentGroup) {
        this.agentGroup = agentGroup;
    }

    public IdleStrategy getIdleStrategy() {
        return idleStrategy;
    }

    public void setIdleStrategy(IdleStrategy idleStrategy) {
        this.idleStrategy = idleStrategy;
    }

    @SneakyThrows
    @SuppressWarnings("unchecked")
    public Service<T> toService() {
        Class<T> serviceClazz = (Class<T>) (serviceClass == null ? service.getClass() : Class.forName(serviceClass));
        serviceClass = serviceClazz.getCanonicalName();
        return new Service<>(service, serviceClazz, name == null ? serviceClass : name);
    }

    @SneakyThrows
    @SuppressWarnings({"unchecked", "all"})
    public <A extends Agent> ServiceAgent<A> toServiceAgent() {
        Service svc = toService();
        return new ServiceAgent<>(agentGroup, idleStrategy, svc, (A) service);
    }

    // -------- Builder API --------
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static final class Builder<T> {
        private T service;
        private String serviceClass;
        private String name;
        private String agentGroup;
        private IdleStrategy idleStrategy;

        private Builder() {
        }

        public Builder<T> service(T service) {
            this.service = service;
            return this;
        }

        public Builder<T> serviceClass(Class<?> clazz) {
            this.serviceClass = clazz == null ? null : clazz.getCanonicalName();
            return this;
        }

        public Builder<T> serviceClassName(String className) {
            this.serviceClass = className;
            return this;
        }

        public Builder<T> name(String name) {
            this.name = name;
            return this;
        }

        public Builder<T> agent(String agentGroup, IdleStrategy idleStrategy) {
            this.agentGroup = agentGroup;
            this.idleStrategy = idleStrategy;
            return this;
        }

        public ServiceConfig<T> build() {
            ServiceConfig<T> cfg = new ServiceConfig<>();
            cfg.setService(service);
            cfg.setServiceClass(serviceClass);
            cfg.setName(name);
            cfg.setAgentGroup(agentGroup);
            cfg.setIdleStrategy(idleStrategy);
            return cfg;
        }
    }
}
