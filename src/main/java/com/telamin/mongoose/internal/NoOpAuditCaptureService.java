/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.service.audit.MongooseAuditCaptureService;

/**
 * Default impl installed when {@code performanceMonitoring.auditCapture.enabled}
 * is false. Every method is a JIT-elided no-op; {@link #isRecording(String)}
 * returns false unconditionally so callers don't have to special-case
 * the disabled state.
 *
 * <p>The Chronicle-backed impl arrives in Phase 1 and replaces this
 * singleton when the YAML flag is on.
 */
public final class NoOpAuditCaptureService implements MongooseAuditCaptureService {

    public static final NoOpAuditCaptureService INSTANCE = new NoOpAuditCaptureService();

    private NoOpAuditCaptureService() {
    }

    @Override
    public void start(String processorName) {
        // intentionally empty
    }

    @Override
    public void stop(String processorName) {
        // intentionally empty
    }

    @Override
    public boolean isRecording(String processorName) {
        return false;
    }
}