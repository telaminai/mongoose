package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.control.CaptureRig.FakeResources;
import com.telamin.mongoose.control.CaptureRig.ManualIo;
import com.telamin.mongoose.control.event.CaptureCompletion;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.control.validation.ControlInvariants;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Design "Audit capture" and its worked example ("late audit open after stop and replacement"), driven through a real
 * group and its generated control processor on one thread, with every open, close and timeout run when the test says.
 * Each test is judged by the resource ledger and by the audit invariants, independently.
 */
class CaptureAsEffectsTest {

    static class Proc implements DataFlow, Lifecycle {
        final String label;
        Proc(String label) { this.label = label; }
        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void tearDown() { }
        @Override public String toString() { return label; }
    }

    private final Map<String, ProcessorRegistration> reserved = new ConcurrentHashMap<>();
    private ComposingEventProcessorAgent group;
    private ManualIo io;
    private FakeResources resources;

    @BeforeEach
    void group() throws Exception {
        group = new ComposingEventProcessorAgent("G", new EventFlowManager(), null, new DeadWheelScheduler(),
                new ConcurrentHashMap<>());
        io = new ManualIo();
        resources = new FakeResources();
        group.setCaptureIo(io);
        group.setAuditCaptureResources(resources);
        group.onStart();
    }

    @AfterEach
    void clearAgentMark() {
        AgentThreads.exit();
    }

    private ProcessorRegistration add(String name, Proc proc) throws Exception {
        ProcessorRegistration reg = new ProcessorRegistration(name);
        reg.nameReleaser(() -> reserved.remove(name, reg));
        assertNull(reserved.putIfAbsent(name, reg), "name free");
        ControlRequest r = group.addNamedEventProcessor(() -> new NamedEventProcessor(name, proc), reg);
        group.doWork();
        assertEquals(ReplyOutcome.APPLIED, r.reply().getNow(null).outcome());
        return reg;
    }

    private void work() throws Exception {
        for (int i = 0; i < 4; i++) group.doWork();
    }

    private ControlEvidence closeAndCheck(String exportName) throws Exception {
        return closeAndCheck(exportName, false);
    }

    private ControlEvidence closeAndCheck(String exportName, boolean expectCloseFailure) throws Exception {
        group.onClose();
        io.runAll();            // anything still with a helper completes after the close: compensated as an orphan
        ControlAuditSink audit = group.control().audit();
        audit.export(Path.of("target/control-audit/" + exportName + ".yaml"));
        ControlEvidence evidence = ControlEvidence.parse(audit.exportText(), audit.isComplete());
        ControlInvariants.Result result = ControlInvariants.check(evidence);
        assertTrue(result.ok(), result + "\n" + audit.exportText());
        assertEquals("", resources.accountingErrors(), "resource attempt ledger");
        if (expectCloseFailure) {
            assertFalse(resources.leaks().isEmpty(), "a failed close must not claim physical resources are closed");
            assertFalse(resources.failedCloseResources().isEmpty());
            assertEquals(resources.failedCloseResources().size(), group.captureResourceSlotsInUse());
            java.util.Set<Long> recordedFailures = evidence.ofType("EffectOutcome").stream()
                    .filter(r -> "CloseCapture".equals(r.str("effect")) && "failed".equals(r.str("outcome")))
                    .map(r -> r.num("resourceId")).filter(id -> id >= 0).collect(java.util.stream.Collectors.toSet());
            assertEquals(resources.failedCloseResources(), recordedFailures);
        } else {
            assertEquals("", resources.leaks(), "physical resource ledger");
            assertEquals(0, group.captureResourceSlotsInUse(), "successful cleanup returns every physical resource slot");
        }
        assertEquals(0, group.captureResourcesHeld());
        assertEquals(0, group.control().outstanding());
        return evidence;
    }

    private static List<String> rules(ControlEvidence evidence) {
        return evidence.ofType("Decision").stream().map(d -> d.str("rule")).toList();
    }

    /** The design's worked example, step for step. */
    @Test
    void lateOpenAfterStopAndReplacementIsCompensatedNeverInstalled() throws Exception {
        Proc gen7 = new Proc("pricer#7");
        ProcessorRegistration pricer = add("pricer", gen7);

        // 1. AuditStart bound to the first registration: OpenCapture pending, reply deferred
        ControlRequest auditStart = group.auditStart("pricer", pricer);
        work();
        assertFalse(auditStart.reply().isDone(), "deferred until the open settles");
        assertEquals(1, io.opens.size(), "the open is with a helper");

        // 2. Stop: released, the open cancelled; the audit start is answered at once
        ControlRequest stop = group.removeEventProcessorByName("pricer", pricer, null);
        work();
        assertEquals(ReplyOutcome.APPLIED, stop.reply().getNow(null).outcome());
        ControlReply startReply = auditStart.reply().getNow(null);
        assertNotNull(startReply, "answered at once, not when the late completion arrives");
        assertEquals(ReplyOutcome.REFUSED, startReply.outcome());

        // 3. The replacement under the same name
        Proc gen8 = new Proc("pricer#8");
        add("pricer", gen8);

        // 4. The late completion: stale, compensated, never installed on gen 8
        io.runOpen();
        work();
        assertTrue(resources.installed.isEmpty(), "nothing installed anywhere: " + resources.installed);
        assertTrue(resources.opened.get(0).installedOn.isEmpty());

        // 5. The compensating close completes
        io.runClose();
        work();
        assertEquals(1, resources.opened.get(0).closes);

        ControlEvidence evidence = closeAndCheck("worked-example-late-open");
        List<String> rules = rules(evidence);
        assertTrue(rules.contains("auditStartOnStarted") && rules.contains("captureOpenedStale")
                && rules.contains("captureClosed"), rules.toString());
        // the stop's own decision answers the waiting audit start and records the open it left outstanding
        var stopDecision = evidence.ofType("Decision").stream().filter(d -> "stopStarted".equals(d.str("rule")))
                .findFirst().orElseThrow();
        assertTrue(stopDecision.str("effects").contains(auditStart.requestId() + ".2 Reply"), stopDecision.toString());
        assertNotNull(stopDecision.str("pendingCleanup"), stopDecision.toString());
        assertFalse(rules.contains("captureOpened"), "never classified current: " + rules);
    }

    @Test
    void anOpenThatCompletesInstallsRecordsAndAStopUninstallsAndCloses() throws Exception {
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        ControlRequest start = group.auditStart("p", reg);
        work();
        io.runOpen();
        work();
        assertEquals(ReplyOutcome.APPLIED, start.reply().getNow(null).outcome());
        assertEquals(resources.opened.get(0), resources.installed.get(p));
        assertTrue(io.timeoutCancelled.get(0), "the open's timeout is cancelled once it completes");

        ControlRequest again = group.auditStart("p", reg);
        work();
        assertEquals("already recording", again.reply().getNow(null).reason());

        ControlRequest stop = group.auditStop("p", reg);
        work();
        assertEquals(ReplyOutcome.APPLIED, stop.reply().getNow(null).outcome(), "answered once uninstalled");
        assertTrue(resources.installed.isEmpty());
        assertEquals(0, resources.opened.get(0).closes, "the close is with the closer");
        io.runClose();
        work();
        closeAndCheck("capture-start-stop");
    }

    @Test
    void stoppingARecordingProcessorUninstallsBeforeReleaseAndClosesAfter() throws Exception {
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        group.auditStart("p", reg);
        work();
        io.runOpen();
        work();
        ControlRequest stop = group.removeEventProcessorByName("p", reg, null);
        work();
        assertEquals(ReplyOutcome.APPLIED, stop.reply().getNow(null).outcome(), "the stop never waits for the close");
        assertTrue(resources.installed.isEmpty(), "uninstalled as part of the stop");
        io.runClose();
        work();
        ControlEvidence evidence = closeAndCheck("capture-stop-while-recording");
        assertTrue(evidence.ofType("Decision").stream().anyMatch(d -> d.str("pendingCleanup") != null),
                "the stop records the close it left outstanding");
        assertTrue(evidence.ofType("Decision").stream().anyMatch(d -> d.str("cleared") != null),
                "and the completion clears it");
    }

    @Test
    void anOpenThatTimesOutIsAnsweredFailedAndItsLateResourceClosed() throws Exception {
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        ControlRequest start = group.auditStart("p", reg);
        work();
        io.fireTimeout(0);
        work();
        ControlReply reply = start.reply().getNow(null);
        assertEquals(ReplyOutcome.FAILED, reply.outcome());
        assertEquals("open timed out", reply.reason());
        io.runOpen();           // the open completes after all
        work();
        assertTrue(resources.installed.isEmpty(), "a timed-out open is never installed");
        io.runClose();
        work();
        closeAndCheck("capture-open-timeout");
    }

    @Test
    void aDuplicateCompletionHasNoEffect() throws Exception {
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        group.auditStart("p", reg);
        work();
        io.runOpen();
        work();
        // the helper posts the same completion again (an adapter that delivers twice)
        CaptureCompletion first = new CaptureCompletion(1, "x", 99, CaptureCompletion.Kind.OPENED, 1, null);
        ControlAuditSink audit = group.control().audit();
        String opId = ControlEvidence.parse(audit.exportText(), true).requested().stream()
                .filter(q -> q.effect().equals("OpenCapture")).findFirst().orElseThrow().opId();
        long root = Long.parseLong(opId.substring(0, opId.indexOf('.')));
        group.control().complete(new CaptureCompletion(root, opId, 100, CaptureCompletion.Kind.OPENED, 1, null));
        work();
        assertEquals(resources.opened.get(0), resources.installed.get(p), "the live capture is untouched");
        assertEquals(0, resources.opened.get(0).closes);
        ControlEvidence evidence = closeAndCheck("capture-duplicate");
        assertTrue(rules(evidence).contains("captureDuplicate"), rules(evidence).toString());
        assertNotNull(first);
    }

    @Test
    void aDuplicateCompletionCarryingAResourceOfItsOwnHasThatResourceClosed() throws Exception {
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        group.auditStart("p", reg);
        work();
        io.runOpen();
        work();
        io.rerunOpen(0);        // the helper delivers again, with a second physical resource
        work();
        assertEquals(2, resources.opened.size());
        assertEquals(resources.opened.get(0), resources.installed.get(p), "the live capture is untouched");
        io.runClose();
        work();
        assertEquals(1, resources.opened.get(1).closes, "the duplicate's resource is closed");
        assertEquals(0, resources.opened.get(0).closes);
        ControlEvidence evidence = closeAndCheck("capture-duplicate-with-resource");
        assertTrue(rules(evidence).contains("captureDuplicate"), rules(evidence).toString());
    }

    @Test
    void anOpenThatFailsIsAnsweredFailedAndHoldsNothing() throws Exception {
        resources.failOpens = true;
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        ControlRequest start = group.auditStart("p", reg);
        work();
        io.runOpen();
        work();
        assertEquals(ReplyOutcome.FAILED, start.reply().getNow(null).outcome());
        closeAndCheck("capture-open-failed");
    }

    @Test
    void anInstallThatFailsIsCompensated() throws Exception {
        resources.failInstalls = true;
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        ControlRequest start = group.auditStart("p", reg);
        work();
        io.runOpen();
        work();
        assertEquals(ReplyOutcome.FAILED, start.reply().getNow(null).outcome());
        io.runClose();
        work();
        closeAndCheck("capture-install-failed");
    }

    @Test
    void closingTheGroupWhileRecordingAndWhileOpeningLeavesNothingOpen() throws Exception {
        Proc a = new Proc("a");
        Proc b = new Proc("b");
        ProcessorRegistration ra = add("a", a);
        ProcessorRegistration rb = add("b", b);
        group.auditStart("a", ra);
        work();
        io.runOpen();
        work();
        ControlRequest opening = group.auditStart("b", rb);
        work();
        // close with a recording and an opening capture: the open completes only after the group has closed
        ControlEvidence evidence = closeAndCheck("capture-close-group");
        assertEquals(ReplyOutcome.REFUSED, opening.reply().getNow(null).outcome());
        assertTrue(evidence.ofType("CaptureCompensated").size() + evidence.records().stream()
                .filter(r -> r.fallback() && "CaptureCompensated".equals(r.type())).count() >= 1);
    }

    /** Review of PR 5, finding 2: a failed uninstall never closes a resource that may still be receiving records. */
    @Test
    void aFailedUninstallKeepsTheCaptureAndAnswersFailed() throws Exception {
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        group.auditStart("p", reg);
        work();
        io.runOpen();
        work();
        resources.failUninstalls = true;
        ControlRequest failed = group.auditStop("p", reg);
        work();
        assertEquals(ReplyOutcome.FAILED, failed.reply().getNow(null).outcome());
        assertEquals(resources.opened.get(0), resources.installed.get(p), "still installed");
        assertTrue(io.closes.isEmpty(), "and not handed to the closer");
        resources.failUninstalls = false;
        ControlRequest stopped = group.auditStop("p", reg);
        work();
        assertEquals(ReplyOutcome.APPLIED, stopped.reply().getNow(null).outcome(), "a later stop succeeds");
        io.runClose();
        work();
        closeAndCheck("capture-uninstall-failed");
    }

    @Test
    void failedUninstallDuringProcessorStopIsRetriedAfterRelease() throws Exception {
        boolean[] closeSubmittedDuringStop = {false};
        Proc p = new Proc("p") {
            @Override public void stop() {
                closeSubmittedDuringStop[0] = !io.closes.isEmpty();
            }
        };
        ProcessorRegistration reg = add("p", p);
        group.auditStart("p", reg);
        work();
        io.runOpen();
        work();
        resources.failLiveUninstalls = true;
        ControlRequest stop = group.removeEventProcessorByName("p", reg, null);
        work();
        assertEquals(ReplyOutcome.FAILED, stop.reply().getNow(null).outcome(), "report the failed live detach");
        assertFalse(closeSubmittedDuringStop[0], "nothing handed to the closer before stop finishes");
        assertTrue(resources.installed.isEmpty(), "offline uninstall after release clears the failed live uninstall");
        io.runClose();
        assertTrue(resources.findings.isEmpty(), resources.findings.toString());
        closeAndCheck("capture-release-uninstall-failed");
    }

    @Test
    void failedOfflineUninstallRetainsOwnershipForGroupCleanup() throws Exception {
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        group.auditStart("p", reg);
        work();
        io.runOpen();
        work();
        resources.failUninstalls = true;
        group.removeEventProcessorByName("p", reg, null);
        work();
        assertTrue(io.closes.isEmpty(), "cannot close until detached");
        assertEquals(1, group.captureResourcesHeld(), "failed detach must not discard ownership");
        resources.failUninstalls = false;
        group.onClose();
        assertEquals("", resources.leaks());
        assertEquals(0, group.captureResourcesHeld());
    }

    @Test
    void aCloseThatFailsIsRecordedAndCountedOnce() throws Exception {
        resources.failCloses = true;
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        group.auditStart("p", reg);
        work();
        io.runOpen();
        work();
        group.auditStop("p", reg);
        work();
        io.runClose();
        work();
        ControlEvidence evidence = closeAndCheck("capture-close-failed", true);
        assertTrue(rules(evidence).contains("captureCloseFailed"), rules(evidence).toString());
    }

    @Test
    void anOpenTheHelperRefusesIsAnsweredFailed() throws Exception {
        io.rejectNextOpen = true;
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        ControlRequest start = group.auditStart("p", reg);
        work();
        ControlReply reply = start.reply().getNow(null);
        assertEquals(ReplyOutcome.FAILED, reply.outcome());
        assertTrue(reply.reason().contains("could not be handed to a helper"), reply.reason());
        ControlEvidence evidence = closeAndCheck("capture-open-refused");
        assertTrue(rules(evidence).contains("captureOpenNotStarted"), rules(evidence).toString());
    }

    @Test
    void aCloserThatRefusesIsReplacedByAnInlineClose() throws Exception {
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        group.auditStart("p", reg);
        work();
        io.runOpen();
        work();
        io.rejectNextClose = true;
        group.auditStop("p", reg);
        work();
        assertEquals(1, resources.opened.get(0).closes, "closed inline");
        closeAndCheck("capture-closer-refused");
    }

    /** Review of PR 5, finding 1: a dead control processor's group still closes its installed captures. */
    @Test
    void aDeadControlProcessorsGroupStillClosesItsCaptures() throws Exception {
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        group.auditStart("p", reg);
        work();
        io.runOpen();
        work();
        group.control().markDead("simulated: the control processor died");
        group.onClose();
        assertEquals(1, resources.opened.get(0).closes, "closed by the group, without the control processor");
        assertTrue(resources.installed.isEmpty());
        assertEquals("", resources.leaks());
        assertEquals(0, group.captureResourcesHeld());
        assertTrue(group.control().audit().records().stream()
                .anyMatch(r -> r.contains("released without the control processor")), "recorded in the fallback stream");
        assertEquals(0, group.captureResourceSlotsInUse());
        var evidence = ControlEvidence.parse(group.control().audit().exportText(), true);
        var validation = ControlInvariants.check(evidence);
        assertTrue(validation.verified(), validation.toString());
        var records = evidence.records();
        var detachRequest = records.stream().filter(r -> r.type().equals("EffectRequested")
                && "UninstallCapture".equals(r.str("effect")) && Boolean.TRUE.equals(r.fields().get("offline")))
                .findFirst().orElseThrow();
        var detachOutcome = records.stream().filter(r -> r.type().equals("EffectOutcome")
                && detachRequest.str("opId").equals(r.str("opId"))).findFirst().orElseThrow();
        var closeRequest = records.stream().filter(r -> r.type().equals("EffectRequested")
                && detachRequest.str("opId").equals(r.str("causeOp"))).findFirst().orElseThrow();
        var closeOutcome = records.stream().filter(r -> r.type().equals("EffectOutcome")
                && closeRequest.str("opId").equals(r.str("opId"))).findFirst().orElseThrow();
        assertEquals("succeeded", detachOutcome.str("outcome"));
        assertTrue(records.indexOf(detachRequest) < records.indexOf(detachOutcome));
        assertTrue(records.indexOf(detachOutcome) < records.indexOf(closeRequest));
        assertTrue(records.indexOf(closeRequest) < records.indexOf(closeOutcome));
    }

    /** Review of PR 5, finding 4: a decision that answers a command at once is not cleanup. */
    @Test
    void aCommandAnsweredAtOnceIsNotMarkedCleanup() throws Exception {
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        group.auditStop("p", reg);
        work();
        ControlEvidence evidence = closeAndCheck("capture-immediate-reply");
        var decision = evidence.ofType("Decision").stream().filter(d -> "auditStopNotRecording".equals(d.str("rule")))
                .findFirst().orElseThrow();
        assertNull(decision.fields().get("cleanup"), decision.toString());
    }

    @Test
    void anAuditStartForAProcessorThatIsNotRunningIsRefused() throws Exception {
        ControlRequest start = group.auditStart("ghost", null);
        work();
        assertEquals(ReplyOutcome.REFUSED, start.reply().getNow(null).outcome());
        assertEquals("no such processor", start.reply().getNow(null).reason());
        closeAndCheck("capture-unknown");
    }
}
