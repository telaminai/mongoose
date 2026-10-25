package com.telamin.mongoose.extreview2;

import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.control.generated.GroupControlProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Second review, Q5: a throw inside an event cycle of the generated processor leaves {@code processing} true, and
 * every later event is queued as re-entrant and never dispatched, with no exception and no record.
 * The throw is injected from the audit listener (afterEvent path), the only user-reachable throw point that needs no
 * src/main change; a node handler throw takes the same un-finally'd path in processEvent.
 */
class GeneratedProcessorWedgeTest {

    @Test
    void aThrowInsideACycleWedgesLaterEventsSilently() throws Exception {
        GroupControlProcessor p = new GroupControlProcessor();
        List<String> records = new CopyOnWriteArrayList<>();
        boolean[] fail = {false};
        p.setAuditLogProcessor(r -> {
            if (fail[0]) throw new IllegalStateException("injected");
            records.add(r.asCharSequence().toString());
        });
        p.init();
        p.setAuditLogLevel(com.telamin.fluxtion.runtime.audit.EventLogControlEvent.LogLevel.INFO);
        p.start();
        records.clear();
        p.onEvent(new RunIntent(1));
        assertTrue(records.stream().anyMatch(r -> r.contains("Run")), "baseline: a Run input is recorded " + records);

        fail[0] = true;
        assertThrows(IllegalStateException.class, () -> p.onEvent(new RunIntent(2)), "the throw reaches the caller");
        fail[0] = false;
        var f = GroupControlProcessor.class.getDeclaredField("processing");
        f.setAccessible(true);
        boolean stuck = (boolean) f.get(p);
        records.clear();
        assertDoesNotThrow(() -> p.onEvent(new RunIntent(3)), "later events are accepted without error");
        System.out.println("processing after throw=" + stuck + " records after next event=" + records.size());
        assertTrue(stuck, "processing left true after the throw");
        assertTrue(records.isEmpty(), "the next event is silently queued, never dispatched: " + records);
    }
}
