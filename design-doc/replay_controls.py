#!/usr/bin/env python3
"""Replay spec controls (design-doc/spec-replay-recording.md): each removes or reverts one behaviour from a byte copy,
runs its named test, and requires a named assertion failure; every named test must pass unmutated first; every file is
restored and its hash checked. Run from the repository root: python3 design-doc/replay_controls.py [control ...]"""
import hashlib, pathlib, shutil, subprocess, sys, xml.etree.ElementTree as ET, json
M='src/main/java/com/telamin/mongoose/'
R=M+'replay/'
CONTROLS=[
 ('R2-arms-the-processors-clock', R+'GroupRecorder.java', '            if (r != null) r.clock.arm();\n        }\n    }\n\n    /**\n     * Just after', '        }\n    }\n\n    /**\n     * Just after', 'ReplayRecordingAcceptanceTest#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder'),
 ('R2-graph-raised-events-never-pass-dispatch', M+'dutycycle/EventQueueToEventProcessorAgent.java', '                    if (recorder != null) recorder.afterDispatch(sourceName, event, seq, targets);\n', '                    if (recorder != null) { recorder.afterDispatch(sourceName, event, seq, targets); recorder.afterDispatch(sourceName, event, seq, targets); }\n', 'ReplayRecordingAcceptanceTest#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder'),
 ('R3-journalled-nowrap-carries-its-seq', M+'dispatch/EventToQueuePublisher.java', '                        journal == null ? mappedItem : new com.telamin.mongoose.replay.JournalledItem(seq, mappedItem));', '                        mappedItem);', 'JournalSequenceTest#aJournalledNowrapItemCarriesItsSequenceNumber_andIsJournalledOnce'),
 ('R3-cached-items-carry-their-own-seq', M+'dispatch/EventToQueuePublisher.java', '                dispatch(cachedFeedEvent.data(), cachedFeedEvent.sequenceNumber());', '                dispatch(cachedFeedEvent.data(), sequenceNumber);', 'JournalSequenceTest#aLateSubscribersCachedItemsCarryTheirOwnSequenceNumbers'),
 ('R3-journalled-once', M+'dispatch/EventToQueuePublisher.java', '        sequenceNumber++;\n        journalItem(mappedItem, sequenceNumber);\n\n        if (log.isLoggable(Level.FINE)) {\n            log.fine("listenerCount:" + targetQueues.size() + " sequenceNumber:" + sequenceNumber + " publish:" + itemToPublish);', '        sequenceNumber++;\n\n        if (log.isLoggable(Level.FINE)) {\n            log.fine("listenerCount:" + targetQueues.size() + " sequenceNumber:" + sequenceNumber + " publish:" + itemToPublish);', 'JournalSequenceTest#aJournalledNowrapItemCarriesItsSequenceNumber_andIsJournalledOnce'),
 ('R4-records-timer-firings', R+'RecordingScheduler.java', '        if (seq < 0) return action;                     // not a recorded processor\'s timer\n', '        if (seq >= 0) return action;\n', 'ReplayRecordingAcceptanceTest#R4_aTimeoutFiringBetweenInputs_replaysThere'),
 ('R4-replay-never-fires-by-itself', R+'ReplayScheduler.java', '    public long scheduleAfterDelay(long waitTime, Runnable action) {\n        return register(action);', '    public long scheduleAfterDelay(long waitTime, Runnable action) {\n        return super.scheduleAfterDelay(waitTime, action);', 'ReplayRecordingAcceptanceTest#R4_aTimeoutFiringBetweenInputs_replaysThere'),
 ('R5-pins-the-entry-instant', R+'GroupReplayer.java', '        c.clock.play(reads);\n', '', 'ReplayRecordingAcceptanceTest#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder'),
 ('R5-plays-every-read-of-the-cycle', R+'ReplayClock.java', '        return next < r.size() ? r.get(next++) : r.get(r.size() - 1);', '        return r.get(0);', 'ReplayRecordingAcceptanceTest#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder'),
 ('R5-delivers-to-the-processor-alone', M+'dispatch/AbstractEventToInvocationStrategy.java', '        if (!eventProcessorSinks.contains(target)) {\n            throw new IllegalArgumentException("invokerId: " + id + " " + target + " is not registered with this strategy");\n        }\n        ProcessorContext.setCurrentProcessor(target);\n        try {\n            dispatchEvent(event, target);', '        if (!eventProcessorSinks.contains(target)) {\n            throw new IllegalArgumentException("invokerId: " + id + " " + target + " is not registered with this strategy");\n        }\n        ProcessorContext.setCurrentProcessor(target);\n        try {\n            dispatchEvent(event, target);\n            dispatchEvent(event, target);', 'ReplayRecordingAcceptanceTest#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder'),
 ('R6-records-an-admin-command-by-its-args', R+'GroupRecorder.java', '            if (event instanceof AdminCommand admin && admin.getArgs() != null && !admin.getArgs().isEmpty()) {', '            if (false && event instanceof AdminCommand admin && admin.getArgs() != null && !admin.getArgs().isEmpty()) {', 'ReplayRecordingAcceptanceTest#R6_anAdminCommandBetweenInputs_replaysThere'),
 ('D4-marks-a-failed-dispatch', M+'dutycycle/EventQueueToEventProcessorAgent.java', '                    if (recorder != null && attempt == 0) recorder.failed(sourceName, event, t, targets);\n', '', 'ReplayRecordingAcceptanceTest#D4_aFailedDispatchIsMarked_andTheReplayStopsThere'),
 ('R7-the-server-passes-the-groups-thread', M+'MongooseServer.java', '            auditCaptureService.attach(eventProcessor, processorName, logRecordListener,\n                    composingEventProcessorAgentRunner.group()::runOnAgentThread);', '            auditCaptureService.attach(eventProcessor, processorName, logRecordListener);', 'AuditSinkOnAgentThreadTest#theServerHandsTheCaptureServiceTheGroupsThread'),
 ('R7-the-sink-changes-on-the-agent-thread', M+'internal/ChronicleAuditCaptureService.java', '            onAgentThread.execute(() -> {', '            ((java.util.concurrent.Executor) Runnable::run).execute(() -> {', 'AuditSinkOnAgentThreadTest#theCaptureServiceChangesTheSinkOnTheAgentThread'),
 ('R5-a-typed-call-replays-through-the-configured-strategy', M+'dispatch/AbstractEventToInvocationStrategy.java', '            dispatchEvent(event, target);\n        } finally {', '            target.onEvent(event);\n        } finally {', 'TypedCallReplayAcceptanceTest#aServiceCallRecordedAtDispatch_isReplayedAsTheSameCall'),
 ('csv-the-journal-reads-back-its-file', R+'CsvEventJournal.java', '                    put(f.get(0), Long.parseLong(f.get(1)), Base64.getDecoder().decode(f.get(2)));\n', '', 'CsvDurableReplayTest#aRunRecordedToCsv_isReplayedFromTheFilesAlone'),
 ('csv-the-store-reads-back-its-file', R+'CsvReplayStore.java', '                    add(f.get(0), parse(f));\n', '', 'CsvDurableReplayTest#aRunRecordedToCsv_isReplayedFromTheFilesAlone'),
 ('A-a-signal-command-runs-in-an-event-cycle', M+'service/admin/impl/AdminCommandInvoker.java', '            adminCommand.executeAsSignal(eventProcessor);           // option A: in the processor\'s event cycle\n', '            adminCommand.executeCommand();\n', 'SignalAdminCommandTest#aSignalCommandRunsInTheProcessorsEventCycle'),
 ('A-an-unanswered-command-is-an-error', M+'service/admin/impl/AdminCommand.java', '            if (!replied[0]) {\n', '            if (false) {\n', 'SignalAdminCommandTest#aCommandNoHandlerAnswers_isAnsweredWithAnError'),
 ('A-each-invocation-keeps-its-routing', M+'service/admin/impl/AdminCommand.java', '        this.signalRouted = adminCommand.signalRouted;\n', '', 'SignalAdminCommandTest#aSignalCommandIsRecorded_andReplayedAsTheSameCycle'),
 ('A-the-generated-processor-audits-the-command', M+'service/admin/impl/AdminCommandInvoker.java', '            adminCommand.executeAsSignal(eventProcessor);           // option A: in the processor\'s event cycle\n', '            adminCommand.executeCommand();\n', 'GeneratedAdminAuditTest#aSignalCommandIsAuditedAndPropagates_aLambdaIsAuditedButDoesNotPropagate'),
 ('A-the-generated-source-stays-publishable', 'src/test/java/com/telamin/mongoose/replay/generated/AlarmProcessor.java', 'package com.telamin.mongoose.replay.generated;\n', '/* Copyright: DEMO header. All Rights Reserved */\npackage com.telamin.mongoose.replay.generated;\n', 'GeneratedAdminAuditTest#theGeneratedSourceIsPublishable'),
 ('A-a-lambda-command-is-bracketed-by-an-audit-record', M+'service/admin/impl/AdminCommandInvoker.java', '        if (log != null) log.eventReceived(event);\n', '', 'GeneratedAdminAuditTest#aSignalCommandIsAuditedAndPropagates_aLambdaIsAuditedButDoesNotPropagate'),
 ('A-a-lambda-record-carries-the-commands-instant', M+'service/admin/impl/AdminCommandInvoker.java', '        if (clock != null) clock.eventReceived(event);\n', '', 'GeneratedAdminAuditTest#aSignalCommandIsAuditedAndPropagates_aLambdaIsAuditedButDoesNotPropagate'),
]
only=set(sys.argv[1:])
results=[]

def verdict_of(test):
    cls,meth=test.split('#')
    for old in pathlib.Path('target/surefire-reports').glob(f'TEST-*.{cls}.xml'): old.unlink()
    r=subprocess.run(['mvn','-o','-q','test',f'-Dtest={test}','-Dsurefire.failIfNoSpecifiedTests=false'],capture_output=True,text=True)
    found=list(pathlib.Path('target/surefire-reports').glob(f'TEST-*.{cls}.xml'))
    if not found:
        return 'compile-error' if 'COMPILATION' in r.stdout+r.stderr else 'no-report'
    root=ET.parse(found[0]).getroot()
    tcs=[tc for tc in root.iter('testcase') if tc.get('name')==meth or tc.get('name').startswith(meth+'(')]
    if not tcs: return 'not-run'
    kinds=[x.tag for x in tcs[0] if x.tag in('failure','error','skipped')]
    return 'caught' if kinds==['failure'] else ('green' if not kinds else '+'.join(kinds))

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
        v=verdict_of(test)
        verdict={'green':'survived'}.get(v, v)
    finally:
        p.write_bytes(orig)
    restored=hashlib.sha256(p.read_bytes()).hexdigest()==h
    results.append((name,verdict,restored)); print(name, verdict, 'restored' if restored else 'NOT RESTORED', flush=True)
print(sum(1 for r in results if r[1]=='caught'), 'of', len(results), 'caught')
