package com.telamin.mongoose.control;

import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.Test;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

class CaptureCloseBacklogProofTest {
    @Test void blockedCloserCannotExceedPhysicalResourceAdmission() throws Exception {
        var io=new CaptureRig.ManualIo(); var resources=new CaptureRig.FakeResources();
        var group=new ComposingEventProcessorAgent("backlog",new EventFlowManager(),null,new DeadWheelScheduler(),new ConcurrentHashMap<>());
        group.setCaptureIo(io); group.setAuditCaptureResources(resources); group.setCaptureOpenLimit(1); group.setCaptureResourceLimit(3); group.onStart();
        group.addNamedEventProcessor(()->new NamedEventProcessor("p",new CaptureAsEffectsTest.Proc("p")),new ProcessorRegistration("p")); group.doWork();
        try {
            for(int i=0;i<128;i++) {
                var request=group.auditStart("p",null); group.doWork();
                if(i<3) {
                    io.runOpen(); group.doWork(); assertTrue(request.reply().join().applied());
                    group.auditStop("p",null); group.doWork();
                } else {
                    assertEquals(com.telamin.mongoose.control.event.ReplyOutcome.FAILED,request.reply().join().outcome());
                    assertTrue(request.reply().join().reason().contains("resource limit"));
                }
            }
            assertEquals(0,group.captureOpensInFlight());
            assertEquals(3,io.closes.size());
            assertEquals(3,resources.opened.size());
            assertEquals(3,group.control().processor().controlState.resources());
            assertEquals(3,group.control().processor().controlState.retainedRoots());
            assertEquals(3,group.captureResourceSlotsInUse());
            io.runClose(); group.doWork(); assertEquals(2,group.captureResourceSlotsInUse());
            var resumed=group.auditStart("p",null); group.doWork(); io.runOpen(); group.doWork();
            assertTrue(resumed.reply().join().applied()); assertEquals(3,group.captureResourceSlotsInUse());
        } finally { group.onClose(); io.runAll(); AgentThreads.exit(); }
        assertEquals(0,group.captureResourceSlotsInUse());
    }
}
