#!/usr/bin/env python3
"""Replay spec controls (design-doc/spec-replay-recording.md): each removes or reverts one behaviour from a byte copy,
runs its named test, and requires a named assertion failure; every named test must pass unmutated first; every file is
restored and its hash checked. Run from the repository root: python3 design-doc/replay_controls.py [control ...]"""
import hashlib, pathlib, shutil, subprocess, sys, xml.etree.ElementTree as ET, json
M='src/main/java/com/telamin/mongoose/'
R=M+'replay/'
CONTROLS=[
 ('R2-arms-the-processors-clock', R+'GroupRecorder.java', '            if (r != null) r.clock.arm();\n        }\n    }\n\n    /**\n     * Just after', '        }\n    }\n\n    /**\n     * Just after', 'ReplayRecordingAcceptanceTest#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder'),
 ('R2-graph-raised-events-never-pass-dispatch', M+'dutycycle/EventQueueToEventProcessorAgent.java', '                recorder.afterDispatch(sourceName, delivered(event), wrapped ? -1 : seq, targets);\n', '                recorder.afterDispatch(sourceName, delivered(event), wrapped ? -1 : seq, targets);\n                recorder.afterDispatch(sourceName, delivered(event), wrapped ? -1 : seq, targets);\n', 'ReplayRecordingAcceptanceTest#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder'),
 ('R3-journalled-nowrap-carries-its-seq', M+'dispatch/EventToQueuePublisher.java', '                        journal == null ? mappedItem : new JournalledItem(seq, mappedItem));', '                        mappedItem);', 'JournalSequenceTest#aJournalledNowrapItemCarriesItsSequenceNumber_andIsJournalledOnce'),
 ('R3-cached-items-carry-their-own-seq', M+'dispatch/EventToQueuePublisher.java', '                dispatch(cachedFeedEvent.data(), cachedFeedEvent.sequenceNumber());', '                dispatch(cachedFeedEvent.data(), sequenceNumber);', 'JournalSequenceTest#aLateSubscribersCachedItemsCarryTheirOwnSequenceNumbers'),
 ('R3-journalled-once', M+'dispatch/EventToQueuePublisher.java', '        sequenceNumber++;\n        journalItem(mappedItem, sequenceNumber);\n\n        if (log.isLoggable(Level.FINE)) {\n            log.fine("listenerCount:" + targetQueues.size() + " sequenceNumber:" + sequenceNumber + " publish:" + itemToPublish);', '        sequenceNumber++;\n\n        if (log.isLoggable(Level.FINE)) {\n            log.fine("listenerCount:" + targetQueues.size() + " sequenceNumber:" + sequenceNumber + " publish:" + itemToPublish);', 'JournalSequenceTest#aJournalledNowrapItemCarriesItsSequenceNumber_andIsJournalledOnce'),
 ('R4-records-timer-firings', R+'RecordingScheduler.java', '        if (seq < 0) return action;                     // not a recorded processor\'s timer\n', '        if (seq >= 0) return action;\n', 'ReplayRecordingAcceptanceTest#R4_aTimeoutFiringBetweenInputs_replaysThere'),
 ('R4-replay-never-fires-by-itself', R+'ReplayScheduler.java', '        return replaying() ? register(action) : super.scheduleAfterDelay(waitTime, action);', '        return super.scheduleAfterDelay(waitTime, action);', 'ReplayRecordingAcceptanceTest#R4_aTimeoutFiringBetweenInputs_replaysThere'),
 ('R5-pins-the-entry-instant', R+'GroupReplayer.java', '        c.clock.play(reads);\n', '', 'ReplayRecordingAcceptanceTest#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder'),
 ('R5-plays-every-read-of-the-cycle', R+'ReplayClock.java', '        return next < r.size() ? r.get(next++) : r.get(r.size() - 1);', '        return r.get(0);', 'ReplayRecordingAcceptanceTest#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder'),
 ('R5-delivers-to-the-processor-alone', M+'dispatch/AbstractEventToInvocationStrategy.java', '        if (!eventProcessorSinks.contains(target)) {\n            throw new IllegalArgumentException("invokerId: " + id + " " + target + " is not registered with this strategy");\n        }\n        ProcessorContext.setCurrentProcessor(target);\n        try {\n            dispatchEvent(event, target);', '        if (!eventProcessorSinks.contains(target)) {\n            throw new IllegalArgumentException("invokerId: " + id + " " + target + " is not registered with this strategy");\n        }\n        ProcessorContext.setCurrentProcessor(target);\n        try {\n            dispatchEvent(event, target);\n            dispatchEvent(event, target);', 'ReplayRecordingAcceptanceTest#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder'),
 ('R6-records-an-admin-command-by-its-args', R+'GroupRecorder.java', '            if (event instanceof AdminCommand admin && admin.getArgs() != null && !admin.getArgs().isEmpty()) {', '            if (false && event instanceof AdminCommand admin && admin.getArgs() != null && !admin.getArgs().isEmpty()) {', 'ReplayRecordingAcceptanceTest#R6_anAdminCommandBetweenInputs_replaysThere'),
 ('D4-marks-a-failed-dispatch', M+'dutycycle/EventQueueToEventProcessorAgent.java', '                    if (recorder != null && attempt == 0) recorder.failed(sourceName, delivered(event), t, targets);\n', '', 'ReplayRecordingAcceptanceTest#D4_aFailedDispatchIsMarked_andTheReplayStopsThere'),
 ('R7-the-server-passes-the-groups-thread', M+'MongooseServer.java', '            auditCaptureService.attach(eventProcessor, processorName, logRecordListener,\n                    composingEventProcessorAgentRunner.group()::runOnAgentThread);', '            auditCaptureService.attach(eventProcessor, processorName, logRecordListener);', 'AuditSinkOnAgentThreadTest#theServerHandsTheCaptureServiceTheGroupsThread'),
 ('R7-the-sink-changes-on-the-agent-thread', M+'internal/ChronicleAuditCaptureService.java', '            return com.telamin.mongoose.dutycycle.AgentHandoff.submit(onAgentThread, () -> {', '            return com.telamin.mongoose.dutycycle.AgentHandoff.submit(Runnable::run, () -> {', 'AuditSinkOnAgentThreadTest#theCaptureServiceChangesTheSinkOnTheAgentThread'),
 ('R5-a-typed-call-replays-through-the-configured-strategy', M+'dispatch/AbstractEventToInvocationStrategy.java', '            dispatchEvent(event, target);\n        } finally {', '            target.onEvent(event);\n        } finally {', 'TypedCallReplayAcceptanceTest#aServiceCallRecordedAtDispatch_isReplayedAsTheSameCall'),
 ('csv-the-journal-reads-back-its-file', R+'CsvEventJournal.java', '                    put(f.get(0), Long.parseLong(f.get(1)), Base64.getDecoder().decode(f.get(2)));\n', '', 'CsvDurableReplayTest#aRunRecordedToCsv_isReplayedFromTheFilesAlone'),
 ('csv-the-store-reads-back-its-file', R+'CsvReplayStore.java', '                    add(f.get(0), parse(f));\n', '', 'CsvDurableReplayTest#aRunRecordedToCsv_isReplayedFromTheFilesAlone'),
 ('A-a-signal-command-runs-in-an-event-cycle', M+'service/admin/impl/AdminCommandInvoker.java', '                adminCommand.executeAsSignal(eventProcessor);       // option A: in the processor\'s event cycle\n', '                adminCommand.executeCommand();\n', 'SignalAdminCommandTest#aSignalCommandRunsInTheProcessorsEventCycle'),
 ('A-an-unanswered-command-is-an-error', M+'service/admin/impl/AdminCommand.java', '            if (!replied[0]) {\n', '            if (false) {\n', 'SignalAdminCommandTest#aCommandNoHandlerAnswers_isAnsweredWithAnError'),
 ('A-each-invocation-keeps-its-routing', M+'service/admin/impl/AdminCommand.java', '        this.signalRouted = adminCommand.signalRouted;\n', '', 'SignalAdminCommandTest#aSignalCommandIsRecorded_andReplayedAsTheSameCycle'),
 ('A-the-generated-processor-audits-the-command', M+'service/admin/impl/AdminCommandInvoker.java', '                adminCommand.executeAsSignal(eventProcessor);       // option A: in the processor\'s event cycle\n', '                adminCommand.executeCommand();\n', 'GeneratedAdminAuditTest#aSignalCommandIsAuditedAndPropagates_aLambdaIsAuditedButDoesNotPropagate'),
 ('A-the-generated-source-stays-publishable', 'src/test/java/com/telamin/mongoose/replay/generated/AlarmProcessor.java', 'package com.telamin.mongoose.replay.generated;\n', '/* Copyright: DEMO header. All Rights Reserved */\npackage com.telamin.mongoose.replay.generated;\n', 'GeneratedAdminAuditTest#theGeneratedSourceIsPublishable'),
 ('A-a-lambda-command-is-bracketed-by-an-audit-record', M+'service/admin/impl/AdminCommandInvoker.java', '        if (log != null) log.eventReceived(event);\n', '', 'GeneratedAdminAuditTest#aSignalCommandIsAuditedAndPropagates_aLambdaIsAuditedButDoesNotPropagate'),
 ('A-a-lambda-record-carries-the-commands-instant', M+'service/admin/impl/AdminCommandInvoker.java', '        if (clock != null) clock.eventReceived(event);\n', '', 'GeneratedAdminAuditTest#aSignalCommandIsAuditedAndPropagates_aLambdaIsAuditedButDoesNotPropagate'),
 ('B2-a-retried-command-does-not-run-again', M+'service/admin/impl/AdminCommandInvoker.java', '        if (adminCommand.executed()) {\n            // a retry of a dispatch', '        if (false) {\n            // a retry of a dispatch', 'AdminCommandFailureTest#aRaisedEventThatThrows_doesNotRunTheCommandAgain'),
 ('B1-F2-a-refused-caller-is-answered', M+'service/admin/impl/AdminCommandInvoker.java', '            adminCommand.refuse(why);                               // last: the caller is released once it is recorded\n', '', 'AdminCommandFailureTest#aProcessorThatCannotRunTheCycle_answersTheCaller_andTheCommandDoesNotRun'),
 ('F2-a-command-failing-before-it-runs-is-answered', M+'service/admin/impl/AdminCommandInvoker.java', '            adminCommand.refuse(why);                               // last: the caller is released once it is recorded\n', '', 'AdminCommandFailureTest#aCommandThatFailsBeforeItRuns_onEitherPath_answersTheCaller'),
 ('F1-a-refusal-is-reported', M+'service/admin/impl/AdminCommandInvoker.java', '            ErrorReporting.report("AdminCommandInvoker", why, failed, ErrorEvent.Severity.WARNING);\n', '', 'AdminCommandFailureTest#aRefusedCommand_isReportedToOperations'),
 ('F3-an-override-that-refuses-is-bracketed', M+'service/admin/impl/AdminCommandInvoker.java', '                if (adminCommand.executed()) {\n                    throw refused;                                  // the cycle failed after the command ran\n                }', '                throw refused;', 'AdminCommandFailureTest#aProcessorWhoseOverrideRefusesTheCycle_runsTheCommandBracketed'),
 ('review-1-a-ReplayRecord-keeps-the-recording-clock', M+'dutycycle/EventQueueToEventProcessorAgent.java', '                                if (!recorder.pinSyntheticTime(target, time)) eventToInvokeStrategy.setSyntheticTime(target, time);', '                                eventToInvokeStrategy.setSyntheticTime(target, time);', 'ReplayReviewRegressionTest#f1_aReplayRecordInput_isRecordedAsItsEvent_andTheReplayMatches'),
 ('review-2-an-undeliverable-entry-stops-the-replay', R+'GroupReplayer.java', '            } else if (System.nanoTime() - c.waitingSince > config.deliveryTimeout().toNanos()) {', '            } else if (false) {', 'ReplayReviewRegressionTest#f2_aMissingAdminCommand_stopsTheReplayWithAReason_ratherThanStalling'),
 ('review-3-a-named-input-is-recorded-as-its-item', R+'GroupRecorder.java', '            } else if (event instanceof com.telamin.fluxtion.runtime.event.NamedFeedEvent<?> named) {', '            } else if (false && event instanceof com.telamin.fluxtion.runtime.event.NamedFeedEvent<?> named) {', 'ReplayReviewRegressionTest#f3_anInlineNamedEventInput_isRecordedToACsvStore_andReplays'),
 ('review-4-record-refuses-a-recording', M+'MongooseServer.java', '        refuseRecordingOverARecording(mongooseServerConfig == null ? null : mongooseServerConfig.getReplay());\n', '', 'ReplayReviewRegressionTest#f4_recordingIntoAJournalThatAlreadyHoldsARecording_isRefused'),
 ('review-5-a-torn-last-line-is-dropped', R+'Csv.java', '        if (!text.isEmpty() && !text.endsWith("\\n") && !lines.isEmpty()) {', '        if (false) {', 'ReplayReviewRegressionTest#f5_aTornLastLine_isDropped_andTheRestIsRead'),
 ('review-6-L1-live-inputs-are-muted', M+'dutycycle/ComposingEventProcessorAgent.java', '            agent.muteLiveInputs(subscriber);\n', '', 'ReplayReviewRegressionTest#f6_aLiveInputDuringAReplay_doesNotReachTheReplayedProcessor'),
 ('review-6-L2-outputs-are-captured', M+'dutycycle/ComposingEventProcessorAgent.java', '        return replayer == null ? service : replayer.serviceFor(eventProcessor, service);', '        return service;', 'ReplayReviewRegressionTest#l2_aReplayedProcessorsOutputs_areCaptured_andNeverDelivered'),
 ('review-7-other-processors-keep-live-timers', R+'ReplayScheduler.java', '        return replaying() ? register(action) : super.scheduleAfterDelay(waitTime, action);', '        return register(action);', 'ReplayReviewRegressionTest#f7_aProcessorNotReplayed_keepsItsLiveTimers'),
 ('review-8-a-clock-divergence-is-reported', R+'GroupReplayer.java', '                return readsMatch(c, in.reads());', '                return true;', 'ReplayReviewRegressionTest#f8_aProcessorReadingItsClockOtherThanRecorded_isReported'),
 ('review-9-a-throwing-timer-is-a-failure', R+'RecordingScheduler.java', '                recorder.timerFailed(flow, seq, failed);    // a timer that throws is a failure (D4), not a firing', '                recorder.timerFired(flow, seq);', 'ReplayReviewRegressionTest#f9_aTimerThatThrows_isRecordedAsAFailure'),
 ('review-11-handed-over-work-runs-at-most-once', M+'dutycycle/AgentHandoff.java', '        if (!claimed.compareAndSet(false, true)) return;', '        claimed.set(true);', 'com.telamin.mongoose.dutycycle.AgentHandoffTest#workTheCallerStoppedWaitingFor_neverRuns'),
 ('review-F4-only-a-first-attempt-is-recorded', M+'dutycycle/EventQueueToEventProcessorAgent.java', '            if (done && recorder != null && attempt == 0) {', '            if (done && recorder != null) {', 'ReplayReviewRegressionTest#f12_aDispatchThatARetryRecovers_isRecordedAsFailedAlone'),
 ('review-11-a-refused-start-closes-its-sink', M+'internal/ChronicleAuditCaptureService.java', '            sink.closeRecording();\n            throw notApplied;', '            throw notApplied;', 'AuditSinkOnAgentThreadTest#aStartThatTimesOut_changesNothing_evenWhenItsInstallRunsLater'),
]
only=set(sys.argv[1:])
results=[]

# an await that ran out of time is a failure too, but not by the named assertion: flagged, for the reader to judge
TIMEOUT_MARKS=('expected ', ' lines:', 'did not complete', 'neither completed nor stopped')
def classify(tc):
    kinds=[x for x in tc if x.tag in('failure','error','skipped')]
    if not kinds: return 'green', ''
    if [k.tag for k in kinds]!=['failure']: return '+'.join(k.tag for k in kinds), ((kinds[0].get('message') or kinds[0].get('type') or ''))[:160]
    msg=(kinds[0].get('message') or '')
    timeout=any(m in msg for m in TIMEOUT_MARKS[2:]) or (msg.startswith(TIMEOUT_MARKS[0]) and TIMEOUT_MARKS[1] in msg)
    return ('caught-by-timeout' if timeout else 'caught'), msg[:160]

def run_and_read(test):
    """Run one test; its verdict and failure message. A dotted class is fully qualified; a bare one is found by its
    simple name among the reports (any package), and two classes of that name are refused, not guessed."""
    cls,meth=test.split('#')
    pattern=f'TEST-{cls}.xml' if '.' in cls else f'TEST-*.{cls}.xml'
    for old in pathlib.Path('target/surefire-reports').glob(pattern): old.unlink()
    r=subprocess.run(['mvn','-o','-q','test',f'-Dtest={test}','-Dsurefire.failIfNoSpecifiedTests=false'],capture_output=True,text=True)
    found=list(pathlib.Path('target/surefire-reports').glob(pattern))
    if len(found)>1: return 'ambiguous', ' '.join(f.name for f in found)
    if not found:
        return ('compile-error' if 'COMPILATION' in r.stdout+r.stderr else 'no-report'), ''
    root=ET.parse(found[0]).getroot()
    tcs=[tc for tc in root.iter('testcase') if tc.get('name')==meth or tc.get('name').startswith(meth+'(')]
    if not tcs: return 'not-run', ''
    return classify(tcs[0])

def verdict_of(test):
    return run_and_read(test)[0]

# every named test must pass UNMUTATED first, or a 'caught' means nothing
baselines={}
for name,path,old,new,test in CONTROLS:
    if only and name not in only: continue
    if test not in baselines:
        baselines[test]=verdict_of(test)
        print('baseline', test, baselines[test], flush=True)
        assert baselines[test]=='green', ('the named test is not green unmutated', test, baselines[test])

for name,path,old,new,test in CONTROLS:
    if only and name not in only: continue
    p=pathlib.Path(path); orig=p.read_bytes(); h=hashlib.sha256(orig).hexdigest()
    text=orig.decode()
    assert text.count(old)==1,(name,'anchor count',text.count(old))
    try:
        p.write_text(text.replace(old,new,1))
        verdict,message=run_and_read(test)
        if verdict=='green': verdict='survived'
    finally:
        p.write_bytes(orig)
    restored=hashlib.sha256(p.read_bytes()).hexdigest()==h
    results.append((name,verdict,restored)); print(name, verdict, 'restored' if restored else 'NOT RESTORED', '|', message, flush=True)
detected=('caught','caught-by-timeout','error')
print(sum(1 for r in results if r[1]=='caught'), 'of', len(results), 'caught by the named assertion;',
      sum(1 for r in results if r[1]=='caught-by-timeout'), 'by an await running out;',
      sum(1 for r in results if r[1]=='error'), 'by an error (read its message: a hang shows as a TimeoutException);',
      sum(1 for r in results if r[1] not in detected), 'NOT detected')
