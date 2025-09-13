/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service;

import com.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.fluxtion.runtime.service.Service;
import com.telamin.mongoose.internal.ServiceInjector;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ServiceInjectorTest {

    public interface Alpha {
        String name();
    }

    public interface Beta {
        int value();
    }

    static class AlphaImpl implements Alpha {
        private final String name;

        AlphaImpl(String n) {
            this.name = n;
        }

        public String name() {
            return name;
        }
    }

    public static class BetaImpl implements Beta {
        private final int v;

        BetaImpl(int v) {
            this.v = v;
        }

        public int value() {
            return v;
        }
    }

    public static class ConsumerService {
        Alpha injectedAlpha;
        String injectedAlphaName;
        Beta injectedBeta;

        @ServiceRegistered
        public void alpha(Alpha alpha) {
            this.injectedAlpha = alpha;
        }

        @ServiceRegistered
        public void beta(Beta beta, String name) {
            this.injectedBeta = beta;
            this.injectedAlphaName = name;
        }
    }

    @Test
    void injectsAnnotatedMethods() {
        ConsumerService consumer = new ConsumerService();
        Service<Alpha> alphaSvc = new Service<>(new AlphaImpl("A1"), Alpha.class, "alphaService");
        Service<Beta> betaSvc = new Service<>(new BetaImpl(42), Beta.class, "betaService");
        Collection<Service<?>> services = Arrays.asList(alphaSvc, betaSvc);

        ServiceInjector.inject(consumer, services);

        assertNotNull(consumer.injectedAlpha, "alpha should be injected");
        assertEquals("A1", consumer.injectedAlpha.name());
        assertNotNull(consumer.injectedBeta, "beta should be injected");
        assertEquals(42, consumer.injectedBeta.value());
        assertEquals("betaService", consumer.injectedAlphaName, "service name should be injected for (svc, name) signature");
    }
}
