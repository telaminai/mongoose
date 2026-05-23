/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.service.audit.AuditSinkHandle;
import com.telamin.mongoose.service.audit.MongooseAuditIntrospectionService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Directory-walking introspection service for audit captures. Lists
 * every per-processor sub-directory under the configured audit
 * directory and merges in the live-sink handles reported by the
 * Chronicle capture service.
 *
 * <p>Per the spec, the result of {@link #listAvailable()} is cached so
 * the steady-state cost is O(1) — the cache is invalidated on each
 * live-sink mutation reported by the capture service (cheap volatile
 * write). Concurrent reads against a stale cache are tolerated; the
 * next read after invalidation rebuilds the snapshot.
 */
public final class DirAuditIntrospectionService implements MongooseAuditIntrospectionService {

    private final AuditCaptureConfig config;
    private final ChronicleAuditCaptureService captureService;
    private final AtomicReference<List<AuditSinkHandle>> cachedList = new AtomicReference<>();

    public DirAuditIntrospectionService(AuditCaptureConfig config, ChronicleAuditCaptureService captureService) {
        this.config = config;
        this.captureService = captureService;
    }

    /** Drop the cached listAvailable() snapshot — call on capture roll / janitor sweep. */
    public void invalidate() {
        cachedList.set(null);
    }

    @Override
    public List<AuditSinkHandle> listAvailable() {
        List<AuditSinkHandle> cached = cachedList.get();
        if (cached != null) {
            return cached;
        }
        List<AuditSinkHandle> fresh = walk();
        // Race-safe: if another walk landed first, just discard ours.
        cachedList.compareAndSet(null, fresh);
        return fresh;
    }

    @Override
    public AuditSinkHandle currentSink(String processorName) {
        return captureService.liveSinks().get(processorName);
    }

    @Override
    public Map<String, AuditSinkHandle> currentSinks() {
        return captureService.liveSinks();
    }

    private List<AuditSinkHandle> walk() {
        Path root = Path.of(config.getDirectory());
        if (!Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        Map<String, AuditSinkHandle> live = captureService.liveSinks();
        List<AuditSinkHandle> out = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isDirectory).forEach(procDir -> {
                String name = procDir.getFileName().toString();
                AuditSinkHandle liveHandle = live.get(name);
                if (liveHandle != null) {
                    // Live capture — handle from the service has authoritative
                    // recordCount + lastWriteAt; reuse verbatim.
                    out.add(liveHandle);
                    return;
                }
                // Closed historical sink — synthesise a handle.
                long size = 0;
                Instant lastModified = Instant.EPOCH;
                try (Stream<Path> files = Files.walk(procDir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        if (Files.isRegularFile(f)) {
                            size += f.toFile().length();
                            Instant mtime = Files.getLastModifiedTime(f).toInstant();
                            if (mtime.isAfter(lastModified)) {
                                lastModified = mtime;
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // best effort — handle still emitted with size=so-far
                }
                out.add(new AuditSinkHandle(
                        name, name, procDir,
                        -1L,       // cycle: unknown from a closed dir walk
                        size,
                        -1L,       // recordCount: -1 = needs a tailer walk; deferred
                        Instant.EPOCH,           // startedAt: unknown without metadata file
                        lastModified,
                        false));
            });
        } catch (IOException e) {
            return Collections.unmodifiableList(out);
        }
        // Sort: live captures first (newest startedAt-ish), then by lastWriteAt desc.
        out.sort((a, b) -> {
            if (a.isLive() != b.isLive()) return a.isLive() ? -1 : 1;
            return b.lastWriteAt().compareTo(a.lastWriteAt());
        });
        // Merge: also include any live captures whose processor dir doesn't yet
        // exist on disk (very short race window between create + first write).
        for (Map.Entry<String, AuditSinkHandle> e : live.entrySet()) {
            boolean present = false;
            for (AuditSinkHandle h : out) {
                if (h.processorName().equals(e.getKey())) { present = true; break; }
            }
            if (!present) {
                out.add(0, e.getValue());
            }
        }
        Map<String, AuditSinkHandle> deduped = new HashMap<>();
        for (AuditSinkHandle h : out) {
            deduped.putIfAbsent(h.processorName(), h);
        }
        return Collections.unmodifiableList(new ArrayList<>(deduped.values()));
    }
}
