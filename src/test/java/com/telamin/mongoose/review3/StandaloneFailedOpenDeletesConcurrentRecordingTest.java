package com.telamin.mongoose.review3;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.internal.ChronicleAuditCaptureService;
import com.telamin.mongoose.service.counters.MongooseCountersService;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 3: standalone (unrouted) capture shares root/<name>. Opener A exclusively creates it, then its build fails;
 * opener B found the directory existing and opened a real queue in it. A's failed-open cleanup walks and deletes the
 * whole directory, including B's live queue files, while B still reports RECORDING. The live directory must survive the failed competing open.
 */
class StandaloneFailedOpenDeletesConcurrentRecordingTest {
    static class Flow implements DataFlow {
        public void onEvent(Object event) { }
        public void addEventFeed(EventFeed feed) { }
        public void setAuditLogProcessor(LogRecordListener listener) { }
    }

    @Test
    void failedOpenRemovesTheDirectoryAConcurrentStartIsRecordingInto(@TempDir Path dir) throws Exception {
        var cfg = new AuditCaptureConfig(); cfg.setEnabled(true); cfg.setDirectory(dir.toString());
        Class<?> noop = Class.forName("com.telamin.mongoose.internal.NoOpCountersService");
        Object counters = noop.getField("INSTANCE").get(null);
        CountDownLatch aInBuilder = new CountDownLatch(1), releaseA = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Function<Path, ChronicleQueue> builder = path -> {
            if (calls.incrementAndGet() == 1) {
                aInBuilder.countDown();
                try { releaseA.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new IllegalStateException("A: transient build failure");
            }
            return SingleChronicleQueueBuilder.binary(path).build();
        };
        Constructor<ChronicleAuditCaptureService> ctor = ChronicleAuditCaptureService.class.getDeclaredConstructor(
                AuditCaptureConfig.class, MongooseCountersService.class, Function.class);
        ctor.setAccessible(true);
        ChronicleAuditCaptureService service = ctor.newInstance(cfg, counters, builder);
        service.attach(new Flow(), "p", r -> { });

        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> service.start("p"));
        assertTrue(aInBuilder.await(10, TimeUnit.SECONDS));
        service.start("p");                                  // B: directory exists, real queue opened, recording
        assertTrue(service.isRecording("p"));
        try (var files = Files.list(dir.resolve("p"))) {
            assertTrue(files.findAny().isPresent(), "B's queue files exist");
        }
        releaseA.countDown();
        assertThrows(Exception.class, a::join);

        assertTrue(service.isRecording("p"), "B still claims to be recording");
        assertTrue(Files.exists(dir.resolve("p")), "failed opener must not delete a concurrent live recording");
        service.stop("p");
    }
}
