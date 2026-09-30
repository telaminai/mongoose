#!/usr/bin/env python3
"""Replay spec controls (design-doc/spec-replay-recording.md): each removes or reverts one behaviour from a byte copy,
runs its named test, and requires a named assertion failure; every named test must pass unmutated first; every file is
restored and its hash checked. Run from the repository root: python3 design-doc/replay_controls.py [control ...]"""
import hashlib, pathlib, shutil, subprocess, sys, xml.etree.ElementTree as ET, json
M='src/main/java/com/telamin/mongoose/'
R=M+'replay/'
CONTROLS=[
 ('R2-arms-the-processors-clock', R+'GroupRecorder.java', '            r.clock.arm();\n            r.received = false;', '            r.received = false;', 'ReplayRecordingAcceptanceTest#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder'),
 ('R2-graph-raised-events-never-pass-dispatch', M+'dutycycle/EventQueueToEventProcessorAgent.java', '                recorder.afterDispatch(sourceName, route, delivered(event), seq, targets);\n', '                recorder.afterDispatch(sourceName, route, delivered(event), seq, targets);\n                recorder.afterDispatch(sourceName, route, delivered(event), seq, targets);\n', 'ReplayRecordingAcceptanceTest#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder'),
 ('R3-journalled-nowrap-carries-its-seq', M+'dispatch/EventToQueuePublisher.java', '                        journal == null ? mappedItem : new JournalledItem(seq, mappedItem));', '                        mappedItem);', 'JournalSequenceTest#aJournalledNowrapItemCarriesItsSequenceNumber_andIsJournalledOnce'),
 ('R3-cached-items-carry-their-own-seq', M+'dispatch/EventToQueuePublisher.java', '                dispatch(cachedFeedEvent.data(), cachedFeedEvent.sequenceNumber());', '                dispatch(cachedFeedEvent.data(), sequenceNumber);', 'JournalSequenceTest#aLateSubscribersCachedItemsCarryTheirOwnSequenceNumbers'),
 ('R3-journalled-once', M+'dispatch/EventToQueuePublisher.java', '        sequenceNumber++;\n        journalItem(mappedItem, sequenceNumber);\n\n        if (log.isLoggable(Level.FINE)) {\n            log.fine("listenerCount:" + targetQueues.size() + " sequenceNumber:" + sequenceNumber + " publish:" + itemToPublish);', '        sequenceNumber++;\n\n        if (log.isLoggable(Level.FINE)) {\n            log.fine("listenerCount:" + targetQueues.size() + " sequenceNumber:" + sequenceNumber + " publish:" + itemToPublish);', 'JournalSequenceTest#aJournalledNowrapItemCarriesItsSequenceNumber_andIsJournalledOnce'),
 ('R4-records-timer-firings', R+'RecordingScheduler.java', '        if (seq < 0) return action;                     // not a recorded processor\'s timer\n', '        if (seq >= 0) return action;\n', 'ReplayRecordingAcceptanceTest#R4_aTimeoutFiringBetweenInputs_replaysThere'),
 ('R4-replay-never-fires-by-itself', R+'ReplayScheduler.java', '        return replaying() ? register(action) : super.scheduleAfterDelay(waitTime, action);', '        return super.scheduleAfterDelay(waitTime, action);', 'ReplayRecordingAcceptanceTest#R4_aTimeoutFiringBetweenInputs_replaysThere', 'the replay fired timer 1, which'),
 ('R5-pins-the-entry-instant', R+'GroupReplayer.java', '        c.clock.play(instant, reads);\n', '', 'ReplayRecordingAcceptanceTest#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder', 'clock divergence'),
 ('R5-plays-every-read-of-the-cycle', R+'ReplayClock.java', '        return next < r.size() ? r.get(next++) : r.isEmpty() ? instant : r.get(r.size() - 1);', '        return r.isEmpty() ? instant : r.get(0);', 'ReplayRecordingAcceptanceTest#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder'),
 ('R5-delivers-to-the-processor-alone', M+'dispatch/AbstractEventToInvocationStrategy.java', '        if (!eventProcessorSinks.contains(target)) {\n            throw new IllegalArgumentException("invokerId: " + id + " " + target + " is not registered with this strategy");\n        }\n        ProcessorContext.setCurrentProcessor(target);\n        try {\n            dispatchEvent(event, target);', '        if (!eventProcessorSinks.contains(target)) {\n            throw new IllegalArgumentException("invokerId: " + id + " " + target + " is not registered with this strategy");\n        }\n        ProcessorContext.setCurrentProcessor(target);\n        try {\n            dispatchEvent(event, target);\n            dispatchEvent(event, target);', 'ReplayRecordingAcceptanceTest#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder', 'clock divergence'),
 ('R6-records-an-admin-command-by-its-args', R+'GroupRecorder.java', '            if (event instanceof AdminCommand admin && admin.getArgs() != null && !admin.getArgs().isEmpty()) {', '            if (false && event instanceof AdminCommand admin && admin.getArgs() != null && !admin.getArgs().isEmpty()) {', 'ReplayRecordingAcceptanceTest#R6_anAdminCommandBetweenInputs_replaysThere'),
 ('D4-marks-a-failed-dispatch', M+'dutycycle/EventQueueToEventProcessorAgent.java', '                    if (recorder != null && attempt == 0) recorder.failed(sourceName, delivered(event), t, targets);\n', '', 'ReplayRecordingAcceptanceTest#D4_aFailedDispatchIsMarked_andTheReplayStopsThere'),
 ('R7-the-server-passes-the-groups-thread', M+'MongooseServer.java', '            auditCaptureService.attach(eventProcessor, processorName, logRecordListener,\n                    composingEventProcessorAgentRunner.group()::runOnAgentThread);', '            auditCaptureService.attach(eventProcessor, processorName, logRecordListener);', 'AuditSinkOnAgentThreadTest#theServerHandsTheCaptureServiceTheGroupsThread'),
 ('R7-the-sink-changes-on-the-agent-thread', M+'internal/ChronicleAuditCaptureService.java', '            return com.telamin.mongoose.dutycycle.AgentHandoff.submit(onAgentThread, () -> {', '            return com.telamin.mongoose.dutycycle.AgentHandoff.submit(Runnable::run, () -> {', 'AuditSinkOnAgentThreadTest#theCaptureServiceChangesTheSinkOnTheAgentThread'),
 ('R5-a-typed-call-replays-through-the-configured-strategy', M+'dispatch/AbstractEventToInvocationStrategy.java', '            dispatchEvent(event, target);\n        } finally {', '            target.onEvent(event);\n        } finally {', 'TypedCallReplayAcceptanceTest#aServiceCallRecordedAtDispatch_isReplayedAsTheSameCall'),
 ('csv-the-journal-reads-back-its-file', R+'CsvEventJournal.java', '                    put(f.get(0), Long.parseLong(f.get(1)), Base64.getDecoder().decode(f.get(2)));\n', '', 'CsvDurableReplayTest#aRunRecordedToCsv_isReplayedFromTheFilesAlone', 'the journal holds no orders#1'),
 ('csv-the-store-reads-back-its-file', R+'CsvReplayStore.java', '                    add(f.get(0), fields == 8 ? parse(f) : parse6(f));\n', '', 'CsvDurableReplayTest#aRunRecordedToCsv_isReplayedFromTheFilesAlone', 'lines=[]'),
 ('review-1-a-ReplayRecord-keeps-the-recording-clock', M+'dutycycle/EventQueueToEventProcessorAgent.java', '                                if (!recorder.pinSyntheticTime(target, time)) eventToInvokeStrategy.setSyntheticTime(target, time);', '                                eventToInvokeStrategy.setSyntheticTime(target, time);', 'ReplayReviewRegressionTest#f1_aReplayRecordInput_isRecordedAsItsEvent_andTheReplayMatches'),
 ('review-2-an-undeliverable-entry-stops-the-replay', R+'GroupReplayer.java', '            } else if (System.nanoTime() - c.waitingSince > config.deliveryTimeout().toNanos()) {', '            } else if (false) {', 'ReplayReviewRegressionTest#f2_aMissingAdminCommand_stopsTheReplayWithAReason_ratherThanStalling', 'neither completed nor stopped'),
 ('review-3-a-named-input-is-recorded-as-its-item', R+'InputCopy.java', '        if (input instanceof NamedFeedEvent<?> named) return', '        if (false && input instanceof NamedFeedEvent<?> named) return', 'ReplayReviewRegressionTest#f3_anInlineNamedEventInput_isRecordedToACsvStore_andReplays'),
 ('review-4-record-refuses-a-recording', M+'MongooseServer.java', '        refuseRecordingOverARecording(mongooseServerConfig == null ? null : mongooseServerConfig.getReplay());\n', '', 'ReplayReviewRegressionTest#f4_recordingIntoAJournalThatAlreadyHoldsARecording_isRefused'),
 ('review-5-a-torn-last-line-is-dropped', R+'Csv.java', '        if (!text.isEmpty() && !text.endsWith("\\n") && !lines.isEmpty()) {', '        if (false) {', 'ReplayReviewRegressionTest#f5_aTornLastLine_isDropped_andTheRestIsRead', 'fields, not'),
 ('review-6-L1-live-inputs-are-muted', M+'dutycycle/ComposingEventProcessorAgent.java', '            agent.muteLiveInputs(subscriber);\n', '', 'ReplayReviewRegressionTest#f6_aLiveInputDuringAReplay_doesNotReachTheReplayedProcessor'),
 ('review-6-L2-outputs-are-captured', M+'dutycycle/ComposingEventProcessorAgent.java', '        return replayer == null ? service : replayer.serviceFor(eventProcessor, service);', '        return service;', 'ReplayReviewRegressionTest#l2_aReplayedProcessorsOutputs_areCaptured_andNeverDelivered'),
 ('review-7-other-processors-keep-live-timers', R+'ReplayScheduler.java', '        return replaying() ? register(action) : super.scheduleAfterDelay(waitTime, action);', '        return register(action);', 'ReplayReviewRegressionTest#f7_aProcessorNotReplayed_keepsItsLiveTimers'),
 ('review-8-a-clock-divergence-is-reported', R+'GroupReplayer.java', '                return readsMatch(c, in.reads());', '                return true;', 'ReplayReviewRegressionTest#f8_aProcessorReadingItsClockOtherThanRecorded_isReported'),
 ('review-9-a-throwing-timer-is-a-failure', R+'RecordingScheduler.java', '                recorder.timerFailed(flow, seq, failed);    // a timer that throws is a failure (D4), not a firing', '                recorder.timerFired(flow, seq);', 'ReplayReviewRegressionTest#f9_aTimerThatThrows_isRecordedAsAFailure'),
 ('review-11-handed-over-work-runs-at-most-once', M+'dutycycle/AgentHandoff.java', '        if (!claimed.compareAndSet(false, true)) return;', '        claimed.set(true);', 'com.telamin.mongoose.dutycycle.AgentHandoffTest#workTheCallerStoppedWaitingFor_neverRuns'),
 ('review-F4-only-a-first-attempt-is-recorded', M+'dutycycle/EventQueueToEventProcessorAgent.java', '            if (done && recorder != null && attempt == 0) {', '            if (done && recorder != null) {', 'ReplayReviewRegressionTest#f12_aDispatchThatARetryRecovers_isRecordedAsFailedAlone'),
 ('review-reA-a-replayed-processors-clock-is-not-replaced', M+'dispatch/AbstractEventToInvocationStrategy.java', '        if (anyMuted && mutedForReplay.contains(eventProcessor)) return;\n', '', 'ReplayReviewRegressionTest#reA_aLiveReplayRecordDuringAReplay_doesNotReplaceTheReplayClock'),
 ('review-reB-a-journal-failure-never-escapes-publish', M+'dispatch/EventToQueuePublisher.java', '        } catch (Throwable failed) {\n            // never out of publish', '        } catch (Error failed) {\n            // never out of publish', 'ReplayReviewRegressionTest#reB_aJournalFailure_neverEscapesPublish_andTheItemIsStillDeliveredWithItsNumber', 'DEMO disk full'),
 ('review-11-a-refused-start-closes-its-sink', M+'internal/ChronicleAuditCaptureService.java', '            sink.closeRecording();\n            throw notApplied;', '            throw notApplied;', 'AuditSinkOnAgentThreadTest#aStartThatTimesOut_changesNothing_evenWhenItsInstallRunsLater'),
 # the independent review of 90f0d9b (findings 1-7); each detected by a named assertion
 ('ir-1-attach-contains-an-unreadable-store', R+'GroupReplayer.java', '            } catch (Throwable t) {\n                failed = "the replay store could not be read: " + t;', '            } catch (Error t) {\n                failed = "the replay store could not be read: " + t;', 'ReplayIndependentReviewTest#f1_aReplayStoreThatCannotBeRead_isAStoppedReplay_notAnExitedServer'),
 ('ir-1-delivery-contains-an-Error', R+'GroupReplayer.java', '            } catch (Throwable e) {\n                // a replay that cannot deliver', '            } catch (RuntimeException e) {\n                // a replay that cannot deliver', 'ReplayIndependentReviewTest#f1_aDecoderThatThrowsAnError_isAStoppedReplay_notAnExitedServer'),
 ('ir-1-a-failed-replay-stays-muted', R+'GroupReplayer.java', '        for (Cursor c : cursors) {\n            if (c.flow == flow) return true;\n        }\n        return false;', '        for (Cursor c : cursors) {\n            if (c.flow == flow && c.stopped == null) return true;\n        }\n        return false;', 'ReplayIndependentReviewTest#f1_aReplayStoreThatCannotBeRead_isAStoppedReplay_notAnExitedServer'),
 ('ir-2-an-input-is-copied-before-dispatch', R+'InputCopy.java', '        return CODEC.decode(CODEC.encode(input));', '        return input;', 'ReplayIndependentReviewTest#f2_anInputTheHandlerChanges_replaysAsReceived_memoryStore'),
 ('ir-2-each-fanned-out-input-is-copied-at-its-own-dispatch', M+'dispatch/AbstractEventToInvocationStrategy.java', '    public void processEventRecording(Object event, java.util.function.BiConsumer<DataFlow, Object> beforeEach) {\n        for (', '    public void processEventRecording(Object event, java.util.function.BiConsumer<DataFlow, Object> beforeEach) {\n        EventToInvokeStrategy.super.processEventRecording(event, beforeEach);\n        if (true) return;\n        for (', 'ReplayIndependentReviewTest#f2_fannedOut_eachProcessorsRecordingIsWhatItReceived'),
 ('ir-2-a-replay-copies-its-input-again', R+'GroupReplayer.java', '        return InputCopy.of(recorded);', '        return recorded;', 'ReplayIndependentReviewTest#f2_aReplayedHandlerThatChangesItsInput_doesNotChangeTheRecording'),
 ('ir-2-an-uncopyable-input-is-marked-failed', R+'GroupRecorder.java', '            } else if (r.uncopyable != null) {', '            } else if (false) {', 'ReplayIndependentReviewTest#f2_anInputThatCannotBeCopied_isRecordedFailed_notByReference'),
 ('ir-3-a-named-event-is-rebuilt-as-itself', R+'GroupReplayer.java', 'event instanceof RecordedNamedEvent named ? named.rebuild(materialised(named.data()))', 'event instanceof RecordedNamedEvent named ? named.data()', 'ReplayIndependentReviewTest#f3_anApplicationNamedFeedEvent_onANowrapFeed_replaysAsItself'),
 ('ir-4-the-queue-carries-its-configured-route', M+'dispatch/EventFlowManager.java', 'eventSourceKey.sourceName(),\n                type.name())', 'eventSourceKey.sourceName(),\n                "")', 'ReplayIndependentReviewTest#f4_twoRoutesFromOneSource_onEventSubscribedFirst'),
 ('ir-4-a-replay-takes-the-entrys-route', M+'dutycycle/ComposingEventProcessorAgent.java', '                            && (route.isEmpty() || route.equals(agent.route()))) {', '                            && true) {', 'ReplayIndependentReviewTest#f4_entriesReplayThroughTheRouteTheyName_typedSubscribedFirst'),
 ('ir-4-an-ambiguous-route-is-refused', M+'dutycycle/ComposingEventProcessorAgent.java', '                if (matched.size() > 1) {', '                if (false) {', 'ReplayIndependentReviewTest#f4_anEntryNamingNoRoute_isRefused_whenTwoRoutesDeliverItsSource'),
 ('ir-5-an-interrupt-cancels-unclaimed-work', M+'dutycycle/AgentHandoff.java', '            if (claimed.compareAndSet(false, true)) {\n                return false;                           // cancelled on interrupt', '            if (false) {\n                return false;                           // cancelled on interrupt', 'com.telamin.mongoose.dutycycle.AgentHandoffTest#workWhoseCallerWasInterrupted_beforeItStarted_neverRuns'),
 ('ir-5-an-interrupt-waits-for-running-work', M+'dutycycle/AgentHandoff.java', '            return awaitStarted();\n        }\n    }\n\n    /** Work the agent', '            return false;\n        }\n    }\n\n    /** Work the agent', 'AuditSinkOnAgentThreadTest#aStartInterruptedWhileItsInstallRuns_doesNotCloseTheSinkUnderIt'),
 ('ir-6-a-cycle-with-no-reads-records-none', R+'RecordingClock.java', '        return List.copyOf(reads);', '        return reads.isEmpty() ? List.of(now()) : List.copyOf(reads);', 'ReplayIndependentReviewTest#f6_aCycleThatReadNoClock_replaysAsZeroReads'),
 ('ir-6-the-read-count-must-match-exactly', R+'GroupReplayer.java', '        if (taken == recorded.size()) return true;', '        if (taken == recorded.size() || (recorded.size() == 1 && taken == 0)) return true;', 'ReplayIndependentReviewTest#f6_aRecordedReadThatTheReplayDoesNotTake_isADivergence'),
 ('ir-7-a-torn-store-is-never-appended-to', R+'CsvReplayStore.java', '        if (torn != null) throw Csv.tornRefusal(file, torn);\n', '', 'ReplayIndependentReviewTest#f7_aTornTail_isNeverAppendedTo_andTheFileStaysReadable'),
 ('ir-7-a-torn-store-is-refused-by-record', R+'CsvReplayStore.java', '        return torn != null || earlierFormat || entries.values()', '        return earlierFormat || entries.values()', 'ReplayIndependentReviewTest#f7_aTornTail_isNeverAppendedTo_andTheFileStaysReadable'),
 ('ir-7-a-torn-journal-is-refused-by-record', R+'CsvEventJournal.java', '        return torn != null || !index.isEmpty();', '        return !index.isEmpty();', 'ReplayIndependentReviewTest#f7_aJournalWithATornTail_isNeverAppendedTo_andStaysReadable'),
 # the re-review of 4a18003 (N1-N6); each detected by a named assertion
 ('rr-N1-the-replay-clock-install-is-contained', R+'GroupReplayer.java', '        try {\n            flow.setClockStrategy(clock);\n        } catch (VirtualMachineError e) {\n            throw e;\n        } catch (Throwable t) {\n            failed = "the replay clock could not be installed: " + t;\n        }', '        flow.setClockStrategy(clock);', 'ReplayReReviewTest#n1_aClockThatCannotBeInstalled_neverEndsARealServer_norLetsLiveInputsThrough'),
 ('rr-N1-the-cursor-exists-before-the-setup', R+'GroupReplayer.java', '        Cursor cursor = new Cursor(name, flow, clock);\n        cursors.add(cursor);\n        scheduler.replay(flow);', '        Cursor cursor = new Cursor(name, flow, clock);\n        scheduler.replay(flow);', 'ReplayReReviewTest#n1_aClockThatCannotBeInstalled_isAStoppedMutedReplay_whenAttachedDirectly'),
 ('rr-N2-a-named-event-keeps-its-time', R+'RecordedNamedEvent.java', '        event.setEventTime(eventTime);', '', 'ReplayReReviewTest#n2_aNamedEventsTimeAndDeleteFlag_replayAsReceived_memoryStore'),
 ('rr-N2-a-named-subclass-is-refused', R+'RecordedNamedEvent.java', '        if (event.getClass() != NamedFeedEventImpl.class) {', '        if (false) {', 'ReplayReReviewTest#n2_aNamedEventSubclass_isNeverReplayedAsTheBaseClass_memoryStore'),
 ('rr-N3-a-journalled-input-is-compared-with-the-journal', R+'GroupRecorder.java', '                if (!java.util.Arrays.equals(now, held)) payload = new EncodedInput(dispatchSource, now);', '                if (false) payload = new EncodedInput(dispatchSource, now);', 'ReplayReReviewTest#n3_aJournalledItemFannedOut_replaysWhatEachRecipientReceived'),
 ('rr-N4-the-default-strategy-fails-closed-on-fan-out', M+'service/EventToInvokeStrategy.java', '        Object given = targets.size() <= 1 ? event :', '        Object given = targets.size() >= 0 ? event :', 'ReplayReReviewTest#n4_aDirectStrategyFanningOut_isNeverRecordedAsIfCaptured_andStillDeliversLive'),
 ('rr-N5-a-transient-field-is-refused', R+'InputCopy.java', '        if (dropped != null) {', '        if (false) {', 'ReplayReReviewTest#n5_anInputWithTransientState_isNeverRecordedAsReceived'),
 ('rr-N6-an-earlier-format-store-is-never-appended-to', R+'CsvReplayStore.java', '                        earlierFormat = fields == 6;', '                        earlierFormat = false;', 'ReplayReReviewTest#n6_anEmptyStoreOfTheEarlierFormat_isNeverCorruptedByAnAppend'),
 ('rr-N6-record-refuses-an-earlier-format-store', R+'CsvReplayStore.java', '        return torn != null || earlierFormat || entries', '        return torn != null || entries', 'ReplayReReviewTest#n6_recordIntoAnEmptyStoreOfTheEarlierFormat_leavesAReadableFile'),
 # the re-review of cd52628 (F1-F4); each detected by a named assertion
 ('r3-F1-a-codec-input-is-read-back-by-its-codec', R+'GroupReplayer.java', '        if (recorded instanceof EncodedInput encoded) return codecOf(encoded.source()).decode(encoded.bytes());', '        if (recorded instanceof EncodedInput encoded) return InputCopy.of(codecOf(encoded.source()).decode(encoded.bytes()));', 'ReplayRound3ReviewTest#f1_aNondeterministicFaithfulCodec_replaysTheValue_memory'),
 ('r3-F1-the-fallback-records-the-codecs-bytes', R+'GroupRecorder.java', '                if (!java.util.Arrays.equals(now, held)) payload = new EncodedInput(dispatchSource, now);', '                if (!java.util.Arrays.equals(now, held)) {\n                    r.input = codec.decode(now);\n                    r.notAsJournalled = true;\n                    return;\n                }', 'ReplayRound3ReviewTest#f1_aCodecOnlyInput_neverFallsBackToJavaSerialisation'),
 ('r3-F2-a-journalled-wrapper-keeps-its-fields', R+'GroupRecorder.java', '            if (named) {\n                // a wrapper is recorded', '            if (false) {\n                // a wrapper is recorded', 'ReplayRound3ReviewTest#f2_aWrappedJournalledInput_replaysTheWrapperItReceived_memory'),
 ('r3-F2-an-integer-filter-is-set-back', R+'RecordedNamedEvent.java', '        if (filterId != event.filterId()) {', '        if (false) {', 'ReplayRound3ReviewTest#f2_anIntegerFilterOnTheExactBaseClass_survives_memory'),
 ('r3-F3-the-recording-clock-install-is-contained', R+'GroupRecorder.java', '        } catch (Throwable t) {\n            String why = "the recording could not start', '        } catch (Error t) {\n            String why = "the recording could not start', 'ReplayRound3ReviewTest#f3_aRecordingClockThatCannotBeInstalled_neverEndsARealServer_andTheRecordingSaysSo'),
 ('r3-F4-a-strategy-naming-no-processor-fails-the-recording', M+'dutycycle/EventQueueToEventProcessorAgent.java', '                if (targets.isEmpty() && !subscribed.isEmpty()) {', '                if (false) {', 'ReplayRound3ReviewTest#f4_aStrategyThatNamesNoProcessor_isNeverRecordedAsAnEmptyRecording'),
 # the targeted re-review of 8211858 (G1, G2); each detected by a named assertion
 ('r4-G1-the-recorded-bytes-are-owned', R+'EncodedInput.java', '        bytes = bytes.clone();\n', '', 'ReplayRound4ReviewTest#g1_aReusedEncoderBuffer_neverRewritesARecordedInput'),
 ('r4-G1-a-recorded-input-is-read-as-a-copy', R+'EncodedInput.java', '        return bytes.clone();', '        return bytes;', 'ReplayRound4ReviewTest#g1_aDecoderThatConsumesItsInput_neverRewritesTheNextReplay'),
 ('r4-G1-an-indexed-input-is-decoded-from-a-copy', R+'GroupReplayer.java', ".decode(bytes.clone());   // from a copy: G1's contract", '.decode(bytes);', 'ReplayRound4ReviewTest#g1_anIndexedInputsDecoderThatConsumesItsInput_neverRewritesTheJournalForTheNextReplay'),
 ('r4-G2-only-an-installed-clock-is-pinned', R+'GroupRecorder.java', '        if (r == null || !r.clockInstalled) return false;', '        if (r == null) return false;', 'ReplayRound4ReviewTest#g2_aFailedRecording_leavesALiveReplayRecordsTimeAsItIsOff'),
 ('r4-G1-the-publishers-journal-owns-its-bytes', M+'dispatch/EventToQueuePublisher.java', '            journal.append(name, seq, journalCodec.encode(mappedItem).clone());', '            journal.append(name, seq, journalCodec.encode(mappedItem));', 'ReplayRound4ReviewTest#g1_thePublishersJournal_ownsItsBytes_whenTheCodecReusesItsBuffer'),
 ('r4-G1-a-journal-ref-is-decoded-from-a-copy', R+'GroupReplayer.java', '.decode(bytes.clone());    // a decoder that consumes its input cannot change the journal (G1)', '.decode(bytes);', 'ReplayRound4ReviewTest#g1_aNamedJournalledInputsDecoderThatConsumesItsInput_neverRewritesTheJournal'),
]
only=set(sys.argv[1:])
results=[]

def report_of(test):
    cls,meth=test.split('#')
    fq=cls if '.' in cls else 'com.telamin.mongoose.replay.'+cls
    return fq, meth, pathlib.Path(f'target/surefire-reports/TEST-{fq}.xml')

# an await that ran out of time is a failure too, but not by the named assertion: flagged, for the reader to judge
TIMEOUT_MARKS=('expected ', ' lines:', 'did not complete', 'neither completed nor stopped')
def classify(tc):
    kinds=[x for x in tc if x.tag in('failure','error','skipped')]
    if not kinds: return 'green', ''
    if [k.tag for k in kinds]!=['failure']: return '+'.join(k.tag for k in kinds), (kinds[0].get('message') or '')[:160]
    msg=(kinds[0].get('message') or '')
    timeout=any(m in msg for m in TIMEOUT_MARKS[2:]) or (msg.startswith(TIMEOUT_MARKS[0]) and TIMEOUT_MARKS[1] in msg)
    return ('caught-by-timeout' if timeout else 'caught'), msg[:160]

def verdict_of(test):
    fq,meth,rep=report_of(test)
    if rep.exists(): rep.unlink()
    r=subprocess.run(['mvn','-o','-q','test',f'-Dtest={test}','-Dsurefire.failIfNoSpecifiedTests=false'],capture_output=True,text=True)
    if not rep.exists():
        return 'compile-error' if 'COMPILATION' in r.stdout+r.stderr else 'no-report'
    root=ET.parse(rep).getroot()
    tcs=[tc for tc in root.iter('testcase') if tc.get('name')==meth or tc.get('name').startswith(meth+'(')]
    if not tcs: return 'not-run'
    return classify(tcs[0])[0]

# every named test must pass UNMUTATED first, or a 'caught' means nothing
baselines={}
for control in CONTROLS:
    name,path,old,new,test=control[:5]
    if only and name not in only: continue
    if test not in baselines:
        baselines[test]=verdict_of(test)
        print('baseline', test, baselines[test], flush=True)
        assert baselines[test]=='green', ('the named test is not green unmutated', test, baselines[test])

for control in CONTROLS:
    name,path,old,new,test=control[:5]
    if only and name not in only: continue
    p=pathlib.Path(path); orig=p.read_bytes(); h=hashlib.sha256(orig).hexdigest()
    bak=pathlib.Path(path+'.orig'); shutil.copyfile(p,bak)          # a byte copy on disk, so a crash leaves it
    text=orig.decode()
    assert text.count(old)==1,(name,'anchor count',text.count(old))
    fq,meth,rep=report_of(test)
    try:
        p.write_text(text.replace(old,new,1))
        if rep.exists(): rep.unlink()
        r=subprocess.run(['mvn','-o','-q','test',f'-Dtest={test}','-Dsurefire.failIfNoSpecifiedTests=false'],capture_output=True,text=True)
        verdict,message='no-report',''
        if rep.exists():
            root=ET.parse(rep).getroot()
            tcs=[tc for tc in root.iter('testcase') if tc.get('name')==meth or tc.get('name').startswith(meth+'(')]
            if not tcs: verdict='not-run'
            else:
                verdict,message=classify(tcs[0])
                if verdict=='green': verdict='survived'
        elif 'COMPILATION' in r.stdout+r.stderr: verdict='compile-error'
    finally:
        subprocess.run(['sh','-c','cat "$1" > "$2"','restore',str(bak),str(p)],check=True)
    restored=hashlib.sha256(p.read_bytes()).hexdigest()==h
    if restored: bak.unlink()
    expect=control[5] if len(control)>5 else None
    # a named assertion is a detection; an await running out, or an error, only when its message is the mutation's
    detected = verdict=='caught' or (verdict in ('caught-by-timeout','error') and expect is not None and expect in message)
    results.append((name,verdict,restored,detected)); print(name, verdict, 'restored' if restored else 'NOT RESTORED', '|', message, flush=True)
caught=sum(1 for r in results if r[1]=='caught')
by_message=sum(1 for r in results if r[3] and r[1]!='caught')
undetected=[r[0] for r in results if not r[3]]
unrestored=[r[0] for r in results if not r[2]]
print(caught, 'of', len(results), 'caught by the named assertion;', by_message,
      'by an await or error carrying the mutation\'s expected message;', len(undetected), 'NOT detected', undetected)
if unrestored: print('NOT RESTORED:', unrestored)
# no mutated compiled class is left behind: every restored source is compiled again from clean
if results and not unrestored:
    c=subprocess.run(['mvn','-o','-q','clean','test-compile'],capture_output=True,text=True)
    print('recompiled the restored sources from clean:', 'ok' if c.returncode==0 else 'FAILED')
    if c.returncode!=0: unrestored.append('recompile')
# a gate, not a report: any control not detected, or any file left mutated, fails the run
sys.exit(1 if undetected or unrestored else 0)
