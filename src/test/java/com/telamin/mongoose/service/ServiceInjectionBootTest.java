/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that services registered via MongooseServerConfig receive dependency injection
 * through @ServiceRegistered methods when the MongooseServer boots.
 *
 * Disabled in this CI/runtime due to external artifact resolution constraints.
 * The DI mechanism is validated in ServiceInjectorTest; this test serves as an
 * integration check for normal environments.
 */
@Disabled("Disabled in constrained environment; relies on full boot which requires external artifacts")
public class ServiceInjectionBootTest {

    private MongooseServer server;

    interface AlphaApi { String who(); }
    interface BetaApi { int answer(); }

    static class AlphaService implements AlphaApi {
        private final String id;
        BetaApi injectedBeta;
        String injectedBetaName;

        AlphaService(String id) { this.id = id; }
        @Override public String who() { return id; }

        // dual-parameter signature: (service, name)
        @ServiceRegistered
        public void beta(BetaApi beta, String name) {
            this.injectedBeta = beta;
            this.injectedBetaName = name;
        }
    }

    static class BetaService implements BetaApi {
        private final int value;
        AlphaApi injectedAlpha; // single-parameter signature
        String injectedAlphaName;

        BetaService(int value) { this.value = value; }
        @Override public int answer() { return value; }

        @ServiceRegistered
        public void alpha(AlphaApi alpha) {
            this.injectedAlpha = alpha;
        }

        @ServiceRegistered
        public void alphaWithName(AlphaApi alpha, String name) {
            // ensure we also cover the 2-arg case in the same target
            this.injectedAlpha = alpha;
            this.injectedAlphaName = name;
        }
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void servicesAreInjectedOnBoot() {
        // Arrange: create services
        AlphaService alpha = new AlphaService("alpha#1");
        BetaService beta = new BetaService(42);

        // Configure app with both services by explicit interface to be unambiguous
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addService(alpha, AlphaApi.class, "alphaService");
        cfg.addService(beta,  BetaApi.class,  "betaService");

        // Act: boot server (registerService performs injection)
        server = MongooseServer.bootServer(cfg, null);

        // Assert: Beta received Alpha (single and dual param methods get invoked)
        assertNotNull(beta.injectedAlpha, "Beta should have Alpha injected");
        assertEquals("alpha#1", beta.injectedAlpha.who());
        assertEquals("alphaService", beta.injectedAlphaName, "Beta should see Alpha service name via dual-param method");

        // Assert: Alpha received Beta with name
        assertNotNull(alpha.injectedBeta, "Alpha should have Beta injected via dual-param method");
        assertEquals(42, alpha.injectedBeta.answer());
        assertEquals("betaService", alpha.injectedBetaName, "Alpha should receive Beta service name");
    }
}
