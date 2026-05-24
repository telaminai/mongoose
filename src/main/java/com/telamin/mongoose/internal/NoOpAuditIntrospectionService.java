/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.service.audit.AuditSinkHandle;
import com.telamin.mongoose.service.audit.MongooseAuditIntrospectionService;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Default introspection impl when the capture service is the NoOp.
 * Returns empty / null for every read — the disabled state is
 * indistinguishable from "no captures have been taken yet."
 */
public final class NoOpAuditIntrospectionService implements MongooseAuditIntrospectionService {

    public static final NoOpAuditIntrospectionService INSTANCE = new NoOpAuditIntrospectionService();

    private NoOpAuditIntrospectionService() {
    }

    @Override
    public List<AuditSinkHandle> listAvailable() {
        return Collections.emptyList();
    }

    @Override
    public AuditSinkHandle currentSink(String processorName) {
        return null;
    }

    @Override
    public Map<String, AuditSinkHandle> currentSinks() {
        return Collections.emptyMap();
    }
}