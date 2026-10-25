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
class CapturePermitRegressionTest {
    @AfterEach void clearMark() { AgentThreads.exit(); }
    private ComposingEventProcessorAgent group(CaptureRig.ManualIo io, CaptureRig.FakeResources resources) throws Exception {
        var group=new ComposingEventProcessorAgent("bounded",new EventFlowManager(),null,new DeadWheelScheduler(),new ConcurrentHashMap<>());
        group.setCaptureIo(io); group.setAuditCaptureResources(resources); group.setCaptureOpenLimit(2);
        group.onStart();
        group.addNamedEventProcessor(()->new NamedEventProcessor("p",new CaptureAsEffectsTest.Proc("p")),new ProcessorRegistration("p"));
        group.doWork(); return group;
    }
    @Test void duplicateHelperExecutionCannotReleaseAnotherAttemptsPermit() throws Exception {
        var io=new CaptureRig.ManualIo(); var resources=new CaptureRig.FakeResources(); var group=group(io,resources);
        try {
            group.auditStart("p",null); group.doWork(); io.runOpen(); group.doWork();
            group.auditStop("p",null); group.doWork(); io.runClose(); group.doWork();
            group.auditStart("p",null); group.doWork(); assertEquals(1,group.captureOpensInFlight());
            io.rerunOpen(0); group.doWork(); assertEquals(1,group.captureOpensInFlight());
        } finally { group.onClose(); io.runAll(); }
        assertEquals(0,group.captureOpensInFlight());
    }
    @Test void rejectedTimeoutSchedulingDoesNotLeakPhysicalAdmission() throws Exception {
        var io=new CaptureRig.ManualIo(); var resources=new CaptureRig.FakeResources(); var group=group(io,resources);
        group.setCaptureIo(new CaptureIo() {
            public void open(Runnable task) { io.open(task); }
            public void close(Runnable task) { io.close(task); }
            public Cancellable schedule(Runnable task,long millis) { throw new RejectedExecutionException("timer stopped"); }
        });
        try {
            var refused=group.auditStart("p",null); group.doWork();
            assertEquals(ReplyOutcome.FAILED,refused.reply().join().outcome());
            assertEquals(0,group.captureOpensInFlight(),"no physical open was submitted");
            group.setCaptureIo(io);
            group.auditStart("p",null); group.doWork(); assertEquals(1,group.captureOpensInFlight());
        } finally { group.onClose(); io.runAll(); }
        assertEquals(0,group.captureOpensInFlight());
    }
}
