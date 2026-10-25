/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.service.audit.*;
import com.telamin.mongoose.service.admin.*;
import com.telamin.mongoose.service.servercontrol.MongooseServerAdmin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CaptureHistoryApiTest {
    static final class Flow implements DataFlow {
        public void onEvent(Object event) { }
        public void addEventFeed(EventFeed feed) { }
        public void setAuditLogProcessor(LogRecordListener listener) { }
    }
    static AuditCaptureConfig config(Path path) {
        var config = new AuditCaptureConfig(); config.setEnabled(true); config.setDirectory(path.toString()); return config;
    }
    @Test void latestAndAllCapturesResolveQualifiedOwnershipAcrossRestart(@TempDir Path root) throws Exception {
        var cfg = config(root);
        var capture = new ChronicleAuditCaptureService(cfg, NoOpCountersService.INSTANCE);
        MongooseAuditIntrospectionService introspection = new DirAuditIntrospectionService(cfg, capture);
        var owner = new AuditCaptureIdentity("g/a", "p%", 1);
        capture.attachScoped(new Flow(), owner, r -> { }, Runnable::run);
        capture.startScoped(owner);
        var first = introspection.currentSink(owner.selector());
        capture.stop(owner.selector());
        capture.startScoped(owner);
        var second = introspection.currentSink(owner.selector());
        assertEquals(second.id(), introspection.latestCapture("g/a", "p%").id());
        assertEquals(List.of(second.id(), first.id()), introspection.capturesFor("p%").stream().map(AuditSinkHandle::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> introspection.capturesFor("p%").clear());
        capture.stop(owner.selector());
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(second.path().resolve(".mongoose-capture.properties"))) { properties.load(reader); }
        Instant openedAt = Instant.parse(properties.getProperty("openedAt"));
        Files.setLastModifiedTime(first.path().resolve(".mongoose-capture.properties"), FileTime.from(Instant.now().plusSeconds(3600)));
        MongooseAuditIntrospectionService restarted = new DirAuditIntrospectionService(cfg,
                new ChronicleAuditCaptureService(cfg, NoOpCountersService.INSTANCE));
        assertEquals(second.id(), restarted.latestCapture(owner.selector()).id(), "last-write activity must not reorder known open times");
        assertEquals(openedAt, restarted.latestCapture(owner.selector()).startedAt());
        assertEquals(List.of(second.id(), first.id()), restarted.capturesFor("g/a", "p%").stream().map(AuditSinkHandle::id).toList());
        assertNull(restarted.latestCapture("missing", "p%"));
        assertTrue(restarted.capturesFor("missing", "p%").isEmpty());
        var other = new AuditCaptureIdentity("h", "p%", 1);
        capture.attachScoped(new Flow(), other, r -> { }, Runnable::run);
        capture.startScoped(other);
        try {
            var error = assertThrows(IllegalArgumentException.class, () -> introspection.latestCapture("p%"));
            assertTrue(error.getMessage().contains("g%2Fa/p%25"));
            assertTrue(error.getMessage().contains("h/p%25"));
        } finally { capture.stop(other.selector()); }
    }
    @Test void oldBareDirectoriesRemainDiscoverableWithoutInventedGroupOwnership(@TempDir Path root) throws Exception {
        Files.createDirectory(root.resolve("legacy"));
        Files.writeString(root.resolve("legacy/old.cq4"), "old capture");
        var cfg = config(root);
        MongooseAuditIntrospectionService service = new DirAuditIntrospectionService(cfg,
                new ChronicleAuditCaptureService(cfg, NoOpCountersService.INSTANCE));
        assertEquals(root.resolve("legacy"), service.latestCapture("legacy").path());
        assertNull(service.latestCapture("g", "legacy"));
        assertEquals(1, service.capturesFor("legacy").size());
    }
    static final class Commands implements AdminCommandRegistry {
        final Map<String, AdminFunction<String, String>> commands = new HashMap<>();
        @SuppressWarnings("unchecked") public <OUT, ERR> void registerCommand(String name, AdminFunction<OUT, ERR> command) {
            commands.put(name, (AdminFunction<String, String>) (Object) command);
        }
        public void processAdminCommandRequest(AdminCommandRequest request) { throw new UnsupportedOperationException(); }
        public List<String> commandList() { return List.copyOf(commands.keySet()); }
        List<String> invoke(String... args) {
            var response = new ArrayList<String>();
            commands.get(args[0]).processAdminCommand(List.of(args), response::add, error -> response.add("ERROR " + error));
            return response;
        }
    }
    @Test void adminListAndStatusShowIdSelectorAndPathForLiveAndHistoricalCaptures(@TempDir Path root) {
        var cfg = config(root);
        var capture = new ChronicleAuditCaptureService(cfg, NoOpCountersService.INSTANCE);
        var introspection = new DirAuditIntrospectionService(cfg, capture);
        var commands = new Commands();
        var admin = new MongooseServerAdmin();
        admin.admin(commands); admin.auditCaptureService(capture); admin.auditIntrospectionService(introspection); admin.start();
        var owner = new AuditCaptureIdentity("g", "only", 1);
        capture.attachScoped(new Flow(), owner, r -> { }, Runnable::run);
        assertEquals(List.of("audit: RECORDING only"), commands.invoke("audit.start", "only"));
        var handle = introspection.currentSink("only");
        String identity = "id=" + handle.id() + " selector=g/only path=" + handle.path();
        assertEquals(List.of("audit: only RECORDING " + identity), commands.invoke("audit.status", "only"));
        assertTrue(commands.invoke("audit.status").getFirst().contains(identity));
        assertTrue(commands.invoke("audit.list").getFirst().contains(identity));
        assertEquals(List.of("audit: STOPPED only"), commands.invoke("audit.stop", "only"));
        assertEquals(List.of("audit: only idle latest " + identity), commands.invoke("audit.status", "only"));
        var another = new AuditCaptureIdentity("h", "only", 1);
        capture.attachScoped(new Flow(), another, r -> { }, Runnable::run);
        String error = commands.invoke("audit.status", "only").getFirst();
        assertTrue(error.startsWith("ERROR audit: ambiguous"));
        assertTrue(error.contains("g/only")); assertTrue(error.contains("h/only"));
    }
}
