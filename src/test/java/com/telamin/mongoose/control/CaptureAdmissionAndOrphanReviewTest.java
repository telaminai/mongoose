package com.telamin.mongoose.control;

import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class CaptureAdmissionAndOrphanReviewTest {
    @AfterEach void clearMark() { AgentThreads.exit(); }
    private ComposingEventProcessorAgent group(CaptureRig.ManualIo io, CaptureRig.FakeResources resources) throws Exception {
        var group=new ComposingEventProcessorAgent("bounded",new EventFlowManager(),null,new DeadWheelScheduler(),new ConcurrentHashMap<>());
        group.setCaptureIo(io); group.setAuditCaptureResources(resources); group.setCaptureOpenLimit(2);
        group.onStart();
        group.addNamedEventProcessor(()->new NamedEventProcessor("p",new CaptureAsEffectsTest.Proc("p")),new ProcessorRegistration("p"));
        group.doWork(); return group;
    }
    @Test void timeoutDoesNotReleasePhysicalAdmissionAndActualCompletionDoes() throws Exception {
        var io=new CaptureRig.ManualIo(); var resources=new CaptureRig.FakeResources(); var group=group(io,resources);
        try {
            for (int i=0;i<2;i++) {
                var start=group.auditStart("p",null); group.doWork(); io.fireTimeout(i); group.doWork();
                assertEquals(ReplyOutcome.FAILED,start.reply().join().outcome());
            }
            for (int i=0;i<100;i++) {
                var refused=group.auditStart("p",null); group.doWork();
                assertEquals(ReplyOutcome.FAILED,refused.reply().join().outcome());
                assertTrue(refused.reply().join().reason().contains("limit"));
            }
            assertEquals(2,group.captureOpensInFlight()); assertEquals(2,io.opens.size());
            assertEquals(0,group.control().processor().controlState.pendingOpens());
            io.runOpen(); group.doWork(); io.runClose(); group.doWork();
            assertEquals(1,group.captureOpensInFlight());
            var admitted=group.auditStart("p",null); group.doWork(); assertFalse(admitted.reply().isDone());
            assertEquals(2,group.captureOpensInFlight());
        } finally { group.onClose(); io.runAll(); }
        assertEquals(0,group.captureOpensInFlight()); assertEquals("",resources.leaks());
    }
    @Test void rejectedOpenReleasesAdmission() throws Exception {
        var io=new CaptureRig.ManualIo(); var resources=new CaptureRig.FakeResources(); var group=group(io,resources);
        try {
            io.rejectNextOpen=true;
            var refused=group.auditStart("p",null); group.doWork();
            assertEquals(ReplyOutcome.FAILED,refused.reply().join().outcome());
            assertEquals(0,group.captureOpensInFlight());
        } finally { group.onClose(); io.runAll(); }
    }
    @Test void boundedRealExecutorRejectsExcessWorkWithoutStartingMoreThreads() throws Exception {
        var executor=CaptureIo.Shared.newOpenExecutor(1,1);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var queued=new CountDownLatch(1);
        try {
            executor.execute(()-> { entered.countDown(); try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertTrue(entered.await(5,TimeUnit.SECONDS)); executor.execute(queued::countDown);
            assertThrows(RejectedExecutionException.class,()->executor.execute(()->fail("must not run")));
            assertEquals(1,executor.getLargestPoolSize()); assertEquals(1,executor.getQueue().size());
            release.countDown(); assertTrue(queued.await(5,TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test void cancelledTimeoutsLeaveTheSharedTimerQueueImmediately() throws Exception {
        var timer=CaptureIo.Shared.newTimer();
        try {
            var pending=timer.schedule(()->fail("cancelled timeout ran"),1,TimeUnit.HOURS);
            assertEquals(1,timer.getQueue().size()); pending.cancel(false);
            assertTrue(timer.getQueue().isEmpty(),"cancelled timeout must not stay retained until its original deadline");
        } finally { timer.shutdownNow(); assertTrue(timer.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test void orphanCloseFailureHasItsOwnAuditedOperationAndFailureReason() throws Exception {
        var io=new CaptureRig.ManualIo(); var resources=new CaptureRig.FakeResources(); var group=group(io,resources);
        group.auditStart("p",null); group.doWork(); group.onClose(); resources.failCloses=true; io.runOpen();
        var evidence=ControlEvidence.parse(group.control().audit().exportText(),true);
        var requested=evidence.ofType("EffectRequested").stream().filter(r->"CloseCapture".equals(r.str("effect"))).findFirst().orElseThrow();
        var outcome=evidence.ofType("EffectOutcome").stream().filter(r->requested.str("opId").equals(r.str("opId"))).findFirst().orElseThrow();
        assertEquals("failed",outcome.str("outcome")); assertTrue(outcome.str("reason").contains("simulated close failure"));
        assertNotEquals(outcome.str("opId"),outcome.str("causeOp")); assertEquals(requested.num("resourceId"),outcome.num("resourceId"));
        assertEquals("",resources.accountingErrors()); assertFalse(resources.leaks().isEmpty());
        assertEquals(0,resources.opened.get(0).closes); assertEquals(1,resources.opened.get(0).closeFailures);
    }
}
