package com.telamin.mongoose.control;

import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.control.validation.ControlInvariants;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

class IndependentEvidenceFollowupTest {
    @AfterEach void clearMark() { AgentThreads.exit(); }
    private static String audit(String... records) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<records.length;i++) out.append("---\neventLogRecord:\n    nodeLogs:\n        - node: {seq: ")
                .append(i+1).append(", ").append(records[i]).append("}\n");
        return out.append("---\n").toString();
    }
    @Test void buildAfterClosingMustViolateI7() {
        var text=audit(
            "record: Input, requestId: 1, fact: Close",
            "record: Decision, requestId: 1, rule: closeEmpty, effects: '1.1 Reply(-1)'",
            "record: Reply, requestId: 1, opId: '1.1', outcome: APPLIED, completed: true",
            "record: Input, requestId: 2, fact: Add, generation: 7",
            "record: Decision, requestId: 2, rule: addAdmitted, name: p, generation: 7, effects: '2.1 BuildAndStart(7)'",
            "record: EffectOutcome, requestId: 2, opId: '2.1', effect: BuildAndStart, outcome: succeeded, generation: 7",
            "record: Decision, requestId: 2, rule: started, name: p, generation: 7, effects: '2.2 Reply(7)'",
            "record: Reply, requestId: 2, opId: '2.2', outcome: APPLIED, completed: true");
        var result=ControlInvariants.check(ControlEvidence.parse(text,true));
        assertFalse(result.ok(),"I7 must reject BuildAndStart after Closing; actual: "+result);
    }
    @Test void duplicateCompletionClosingInstalledResourceMustViolateI14() {
        var text=audit(
            "record: Decision, requestId: 1, rule: captureOpened, generation: 7, resourceId: 41, effects: '1.1 InstallCapture(7)'",
            "record: EffectOutcome, requestId: 1, opId: '1.1', effect: InstallCapture, outcome: succeeded, generation: 7",
            "record: Decision, requestId: 1, rule: captureInstalled, generation: 7, effects: ''",
            "record: Decision, requestId: 1, rule: captureDuplicate, duplicate: true, generation: 7, resourceId: 41, effects: '1.c1 CloseCapture(7)'",
            "record: EffectOutcome, requestId: 1, opId: '1.c1', effect: CloseCapture, outcome: succeeded, resourceId: 41");
        var result=ControlInvariants.check(ControlEvidence.parse(text,true));
        assertFalse(result.ok(),"duplicate completion closed installed resource without uninstallation; actual: "+result);
    }
    private ComposingEventProcessorAgent group(CaptureRig.ManualIo io,CaptureRig.FakeResources resources) throws Exception {
        var group=new ComposingEventProcessorAgent("proof",new EventFlowManager(),null,new DeadWheelScheduler(),new ConcurrentHashMap<>());
        group.setCaptureIo(io); group.setAuditCaptureResources(resources); group.onStart();
        group.addNamedEventProcessor(()->new NamedEventProcessor("p",new CaptureAsEffectsTest.Proc("p")),new ProcessorRegistration("p"));
        group.doWork(); return group;
    }
    @Test void orphanPhysicalCloseFailureMustRetainItsFinalOutcome() throws Exception {
        var io=new CaptureRig.ManualIo(); var resources=new CaptureRig.FakeResources();
        var group=group(io,resources);
        group.auditStart("p",null); group.doWork(); group.onClose();
        resources.failCloses=true; io.runOpen();
        String text=group.control().audit().exportText();
        var result=ControlInvariants.check(ControlEvidence.parse(text,true));
        assertFalse(result.verified(),"failed physical closure must not receive an invariant PASS: "+result);
        assertEquals("",resources.accountingErrors(),"each failed attempt must still be accounted for");
        assertFalse(resources.leaks().isEmpty(),"a thrown close does not establish physical closure");
        assertTrue(text.contains("simulated close failure"),"actual physical-close failure is discarded: "+text);
    }
    /**
     * Was a demonstration of unbounded growth (128 unresolved opens after 128 timeouts). Fixed: a timed-out open settles
     * its request, but its physical operation keeps its permit until the helper returns, so at most the group's limit of
     * physical opens are ever in flight; further audit starts are refused while they are; nothing physical is forgotten,
     * and every resource a late helper returns is closed exactly once.
     */
    @Test void timedOutOpenAttemptsAreBoundedByPhysicalOpensInFlight() throws Exception {
        var io=new CaptureRig.ManualIo(); var resources=new CaptureRig.FakeResources();
        var group=group(io,resources);
        int timeouts=0;
        try {
            for(int i=0;i<128;i++) {
                var request=group.auditStart("p",null); group.doWork();
                if (io.timeouts.size()>timeouts) { io.fireTimeout(timeouts++); group.doWork(); }
                assertEquals(com.telamin.mongoose.control.event.ReplyOutcome.FAILED,request.reply().join().outcome());
            }
            assertEquals(0,group.control().outstanding());
            assertEquals(8,io.opens.size(),"physical opens in flight are bounded by the group's limit");
            assertEquals(8,group.captureOpensInFlight());
            assertEquals(0,group.control().processor().controlState.retainedRoots(),"settled requests retain nothing");
        } finally { group.onClose(); io.runAll(); }
        assertEquals(0,group.captureOpensInFlight(),"every permit is returned once its helper returns");
        assertEquals(8,resources.opened.size());
        assertEquals("",resources.leaks(),"each late resource closed exactly once");
    }
}
