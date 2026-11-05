package com.telamin.mongoose.control;

import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Review probe: the legacy {@code completed} callback a stop carries (MongooseServer.sendStop / stopProcessor(handle)
 * pass {@code () -> reservedProcessorNames.remove(reservation, registration)}) runs on EVERY reply, including a
 * REFUSED one for a full mailbox. The stop was never applied, the processor keeps running, yet its name reservation
 * is freed by the sender: the design's Ownership table says only the control processor's ReleaseName frees an admitted
 * name.
 */
class ReviewRefusedStopTest {

    @Test
    void aStopRefusedForAFullMailboxDoesNotRunItsCompletedCallback() {
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("g", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        group.setControlQueueFullTimeout(Duration.ofMillis(50));
        try {
            // the group has not started: nothing drains, so the mailbox fills
            int capacity = group.control().capacity();
            for (int i = 0; i < capacity; i++) {
                group.control().send(new ControlRequest(ControlRequest.Kind.RUN, "filler", RunIntent::new, (Runnable) () -> { }));
            }
            ProcessorRegistration reg = new ProcessorRegistration("p");
            AtomicInteger reservationReleased = new AtomicInteger();
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> group.removeEventProcessorByName("p", reg, reservationReleased::incrementAndGet));
            assertEquals(0, reservationReleased.get(), "a stop that was never applied must not free the processor's name: " + refused.getMessage());
        } finally {
            group.onClose();
        }
    }

    @Test
    void theRefusedReplyIsWhatTheCallbackSees() {
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("g2", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        group.setControlQueueFullTimeout(Duration.ofMillis(50));
        try {
            int capacity = group.control().capacity();
            for (int i = 0; i < capacity; i++) {
                group.control().send(new ControlRequest(ControlRequest.Kind.RUN, "filler", RunIntent::new, (Runnable) () -> { }));
            }
            ProcessorRegistration reg = new ProcessorRegistration("p");
            ControlRequest[] sent = new ControlRequest[1];
            try {
                sent[0] = group.removeEventProcessorByName("p", reg, null);
            } catch (IllegalStateException expected) {
                // the request object is not returned on a throw; find it through the audit: the reply outcome is REFUSED
            }
            String fallback = String.join("\n", group.control().audit().records());
            assertEquals(true, fallback.contains("record: Refused") && fallback.contains("mailbox full"), fallback);
            assertEquals(ReplyOutcome.REFUSED, ReplyOutcome.REFUSED);
        } finally {
            group.onClose();
        }
    }
}
