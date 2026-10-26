package com.telamin.mongoose.review77ext;
import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.control.*;
import com.telamin.mongoose.dutycycle.*;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.audit.AuditCaptureResources;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
public class CaptureAcknowledgementTest {
    static class Proc implements DataFlow { public void init() {} public void addEventFeed(com.telamin.fluxtion.runtime.input.EventFeed f) {} public void onEvent(Object e) {} }
    static class Resources implements AuditCaptureResources {
        boolean detachFails=true;
        int closes;
        public Object openCapture(String name) { return new Object(); }
        public void installCapture(Object r,String name,DataFlow flow) {}
        public void uninstallCapture(Object r,String name,DataFlow flow) { if(detachFails) throw new IllegalStateException("first detach failure"); }
        public void closeCapture(Object r) { closes++; throw new IllegalStateException("NEW physical close failure"); }
    }
    @Test void acknowledgingDetachMustNotAcknowledgeAFutureCloseFailure() throws Exception {
        var group=new ComposingEventProcessorAgent("g",new EventFlowManager(),null,new DeadWheelScheduler(),new ConcurrentHashMap<>());
        var tasks=new ArrayDeque<Runnable>(); var resources=new Resources();
        group.setAuditCaptureResources(resources);
        group.setCaptureIo(new CaptureIo() {
            public void open(Runnable r) { tasks.add(r); }
            public void close(Runnable r) { tasks.add(r); }
            public Cancellable schedule(Runnable r,long millis) { return ()->{}; }
        });
        var reg=new ProcessorRegistration("p");
        group.addNamedEventProcessor(()->new NamedEventProcessor("p",new Proc()),reg);
        group.onStart();
        try {
            var start=group.auditStart("p",reg); drain(group,tasks);
            assertTrue(start.reply().getNow(null).applied());
            var firstStop=group.auditStop("p",reg); drain(group,tasks);
            assertFalse(firstStop.reply().getNow(null).applied());
            assertFalse(group.captureCleanupFailures().isEmpty());
            group.acknowledgeCaptureFailures();
            assertNull(group.unacknowledgedCaptureFailures());
            resources.detachFails=false;
            group.auditStop("p",reg); drain(group,tasks);
            assertEquals(1,resources.closes);
            assertFalse(group.captureCloseFailures().isEmpty(),"a new physical-close failure must be recorded");
            assertNotNull(group.unacknowledgedCaptureFailures(),"acknowledging old detach must not clear an unseen future close failure");
        } finally {
            group.onClose(); while(!tasks.isEmpty()) tasks.remove().run(); AgentThreads.exit();
        }
    }
    static void drain(ComposingEventProcessorAgent g,ArrayDeque<Runnable> tasks) throws Exception {
        for(int i=0;i<5;i++) { while(!tasks.isEmpty()) tasks.remove().run(); g.doWork(); }
    }
}

