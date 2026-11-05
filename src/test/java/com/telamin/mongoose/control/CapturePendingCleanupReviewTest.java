package com.telamin.mongoose.control;

import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

class CapturePendingCleanupReviewTest {
    @AfterEach void clearMark() { AgentThreads.exit(); }
    static final class Rig implements AutoCloseable {
        final CaptureRig.ManualIo io=new CaptureRig.ManualIo();
        final CaptureRig.FakeResources resources=new CaptureRig.FakeResources();
        final ComposingEventProcessorAgent group=new ComposingEventProcessorAgent("cleanup",new EventFlowManager(),null,
                new DeadWheelScheduler(),new ConcurrentHashMap<>());
        final ProcessorRegistration registration=new ProcessorRegistration("p");
        Rig() throws Exception {
            group.setCaptureIo(io); group.setAuditCaptureResources(resources); group.onStart();
            group.addNamedEventProcessor(()->new NamedEventProcessor("p",new CaptureAsEffectsTest.Proc("p")),registration);
            group.doWork();
        }
        void open() throws Exception { group.auditStart("p",registration); group.doWork(); io.runOpen(); group.doWork(); }
        void stopCapture() throws Exception { group.auditStop("p",registration); group.doWork(); }
        ControlEvidence evidence() { return ControlEvidence.parse(group.control().audit().exportText(),true); }
        void assertStopListsEveryClose(int expectedCloses) throws Exception {
            var stop=group.removeEventProcessorByName("p",registration,null); group.doWork();
            var evidence=evidence();
            List<String> closes=evidence.requested().stream().filter(q->q.effect().equals("CloseCapture"))
                    .map(ControlEvidence.Requested::opId).toList();
            assertEquals(expectedCloses,closes.size());
            var decision=evidence.ofType("Decision").stream().filter(d->d.requestId()==stop.requestId()
                    && "stopStarted".equals(d.str("rule"))).findFirst().orElseThrow();
            String pending=decision.str("pendingCleanup"); assertNotNull(pending,decision.toString());
            for(String op:closes) assertTrue(List.of(pending.split(";")).contains(op),"missing outstanding close "+op+": "+decision);
        }
        public void close() { group.onClose(); io.runAll(); }
    }
    @Test void closingStateListsEveryOlderQueuedClose() throws Exception {
        try(var r=new Rig()) { r.open(); r.stopCapture(); r.open(); r.stopCapture(); r.assertStopListsEveryClose(2); }
    }
    @Test void recordingSuccessorListsItsCloseAndOlderQueuedClose() throws Exception {
        try(var r=new Rig()) { r.open(); r.stopCapture(); r.open(); r.assertStopListsEveryClose(2); }
    }
    @Test void offAfterCancelledReopenStillListsOlderQueuedClose() throws Exception {
        try(var r=new Rig()) {
            r.open(); r.stopCapture(); r.group.auditStart("p",r.registration); r.group.doWork(); r.stopCapture();
            r.assertStopListsEveryClose(1);
        }
    }
    @Test void offAfterFailedReopenStillListsOlderQueuedClose() throws Exception {
        try(var r=new Rig()) {
            r.open(); r.stopCapture(); r.resources.failOpens=true; r.open(); r.assertStopListsEveryClose(1);
        }
    }    @Test void completedClosesDoNotRemainInLaterPendingCleanup() throws Exception {
        try(var r=new Rig()) {
            r.open(); r.stopCapture();
            String oldClose=r.evidence().requested().stream().filter(q->q.effect().equals("CloseCapture"))
                    .map(ControlEvidence.Requested::opId).findFirst().orElseThrow();
            r.io.runClose(); r.group.doWork();
            r.open(); r.stopCapture();
            var stop=r.group.removeEventProcessorByName("p",r.registration,null); r.group.doWork();
            var decision=r.evidence().ofType("Decision").stream().filter(d->d.requestId()==stop.requestId()
                    && "stopStarted".equals(d.str("rule"))).findFirst().orElseThrow();
            List<String> pending=List.of(decision.str("pendingCleanup").split(";"));
            assertEquals(1,pending.size()); assertFalse(pending.contains(oldClose));
        }
    }

}
