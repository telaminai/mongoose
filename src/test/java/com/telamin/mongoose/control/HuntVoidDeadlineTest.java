package com.telamin.mongoose.control;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Adversarial hunt: the void control calls' "single deadline" (controlWaitMillis). */
@Timeout(60)
class HuntVoidDeadlineTest {

    /**
     * FM: the void {@code addEventProcessor} and {@code stopProcessor(group, name)} send with no admission deadline: on
     * a full mailbox they park for the separate 10 s full-mailbox budget before their configured wait even starts.
     * Finding 7 of the follow-up review fixed only {@code stopProcessor(group, name, waitMillis)}.
     */
    @Test
    void voidControlCallsMustSpendOneDeadlineOnAdmissionAndReply() throws Exception {
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.setControlWaitMillis(50);
        MongooseServer s = new MongooseServer(cfg);
        s.init();
        s.start();
        HuntServerFailureModesTest.Proc holder = new HuntServerFailureModesTest.Proc();
        holder.blockStart = true;
        try {
            s.addEventProcessor("p", "g", null, HuntServerFailureModesTest.Proc::new);
            s.addProcessor("holder", "g", null, () -> holder);
            assertTrue(holder.startEntered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 4096; i++) s.addProcessor("queued" + i, "g", null, HuntServerFailureModesTest.Proc::new);

            long t0 = System.nanoTime();
            try {
                s.stopProcessor("g", "p");                              // void: documented to wait controlWaitMillis
            } catch (IllegalStateException refusedAfterTheFullTimeout) {
                // thrown once the 10 s full-mailbox budget runs out
            }
            long stopMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            t0 = System.nanoTime();
            try {
                s.addEventProcessor("late", "g", null, HuntServerFailureModesTest.Proc::new);
            } catch (IllegalStateException refusedAfterTheFullTimeout) {
                // thrown once the 10 s full-mailbox budget runs out
            }
            long addMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(stopMs < 1_000, "void stop returned after " + stopMs + " ms with controlWaitMillis=50");
            assertTrue(addMs < 1_000, "void add returned after " + addMs + " ms with controlWaitMillis=50");
        } finally {
            holder.startRelease.countDown();
            s.stop();
        }
    }
}
