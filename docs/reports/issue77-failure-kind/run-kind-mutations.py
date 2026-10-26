#!/usr/bin/env python3
"""Issue #77 milestone 2 source mutations, run against a fresh scratch clone; never edits the supplied repo.

Usage: JAVA_HOME=/path/to/jdk21 TMPDIR=/scratch python3 -I run-kind-mutations.py /path/to/mongoose <commit>

Part 1 re-runs the six PR #76 third-review mutants (the "old" mutants, some formerly caught by reason-text matching)
against the kind-keyed harness and oracle. Part 2 adds mutants that corrupt only the structured FailureKind (round 2
adds the review-77 F1, F2 and F6 paths). Each mutant must compile and be caught: the run fails with at least one failed
or errored test. Errors are counted as catches because a behavioural regression can surface as an unexpected
exception in a test (review 77, F7: the deadline mutant errors in ControlTerminationAndAdmissionTest); compilation
errors are not catches. Requires offline Maven.
"""
import json, pathlib, subprocess, sys, tempfile, time, xml.etree.ElementTree as ET

SHA = sys.argv[2]  # an explicit commit: never qualify a moving branch or a dirty checkout
base = pathlib.Path(tempfile.mkdtemp(prefix="mongoose-kind-mutations-"))
repo = base / "repo"
subprocess.run(["git", "clone", "--no-hardlinks", "--quiet", sys.argv[1], str(repo)], check=True)
subprocess.run(["git", "checkout", "--quiet", "--detach", SHA], cwd=repo, check=True)
node = "src/main/java/com/telamin/mongoose/control/node/"
driver = "src/main/java/com/telamin/mongoose/control/ControlDriver.java"
agent = "src/main/java/com/telamin/mongoose/dutycycle/ComposingEventProcessorAgent.java"
replayer = "src/main/java/com/telamin/mongoose/replay/GroupReplayer.java"

# (name, [(file, before, after, replace_all)])
mutations = [
    # ---- part 1: the PR #76 third-review mutants, unchanged anchors
    ("name-only-stop-without-release", [(node + "LifecycleRules.java", 'switch (reg.lifecycle) {',
        'if (!intent.bound() && reg.lifecycle == Lifecycle.STARTED) {\n'
        '    decide("nameOnlyMutant", reg.generation, reg.name, "Started", "Started",\n'
        '            reply(req, reg.generation, reg.name, ReplyOutcome.APPLIED, "stopped"));\n'
        '    return true;\n'
        '}\n'
        'switch (reg.lifecycle) {', False)]),
    ("missing-stop-reply", [(node + "LifecycleRules.java",
        'decide("stopComplete", result.generation(), result.name(), "Released", "Released", reply);',
        'decide("stopComplete", result.generation(), result.name(), "Released", "Released");', False)]),
    ("applied-without-release-renamed-rule", [(node + "LifecycleRules.java",
        'decide("stopStarted", reg.generation, reg.name, "Started", "Releasing", pendingCleanup,\n'
        '                        requested.toArray(new ControlEffect[0]));',
        'decide("innocentLookingLabel", reg.generation, reg.name, "Started", "Releasing", '
        'reply(req, reg.generation, reg.name, ReplyOutcome.APPLIED, "stopped"));', False)]),
    ("missing-stale-open-close", [(node + "CaptureRules.java",
        'decideCompletion("captureOpenedStale", open.generation, name, c.resourceId(), note("stale", true),\n'
        '                closeEffect(root, open.generation, name, c.resourceId()));',
        'decideCompletion("captureOpenedStale", open.generation, name, c.resourceId(), note("stale", true));', False)]),
    ("skip-terminal-sweep", [(driver, 'if (r.isClaimed()) continue;',
        'if (r.isClaimed() || r.requestId() % 5 == 0) continue;', False)]),
    ("drop-claimed-stop", [(driver, 'claimed.put(request.requestId(), request);',
        'if (request.kind() == ControlRequest.Kind.STOP && request.requestId() % 7 == 0) return;\n'
        '        claimed.put(request.requestId(), request);', False)]),
    # ---- part 2: mutants that corrupt only the structured kind
    ("missing-stop-reply-relabelled-as-effect-failure", [
        (node + "LifecycleRules.java",
         'decide("stopComplete", result.generation(), result.name(), "Released", "Released", reply);',
         'decide("stopComplete", result.generation(), result.name(), "Released", "Released");', False),
        (driver, 'complete(request, FailureKind.FAILED_DRIVER_FALLBACK, "no reply after its effects settled");',
         'complete(request, FailureKind.FAILED_EFFECT, "stopped with errors");', False)]),
    ("applied-reply-classified-as-failure", [(node + "LifecycleRules.java",
        'outcome == ReplyOutcome.APPLIED ? FailureKind.NONE : kind',
        'outcome == ReplyOutcome.APPLIED ? FailureKind.FAILED_EFFECT : kind', False)]),
    ("quarantine-kind-dropped", [(node + "LifecycleRules.java",
        'ReplyOutcome.FAILED, FailureKind.QUARANTINED,', 'ReplyOutcome.FAILED, FailureKind.FAILED_EFFECT,', False)]),
    ("deadline-kind-dropped", [(driver,
        'refuse(request, FailureKind.REFUSED_DEADLINE,', 'refuse(request, FailureKind.REFUSED_ADMISSION,', False)]),
    ("leftover-marker-dropped", [(agent,
        'boolean leftover = leftoverCaptures.contains(id) || !captureDetachFailures.contains(id);',
        'boolean leftover = false;', False)]),
    ("capture-rule-drops-effect-kind", [(node + "CaptureRules.java",
        'answerWaiters(reg, ReplyOutcome.FAILED, result.kind(), "install failed: "',
        'answerWaiters(reg, ReplyOutcome.FAILED, com.telamin.mongoose.control.event.FailureKind.FAILED_EFFECT, "install failed: "', False)]),
    ("service-rule-drops-effect-kind", [(node + "ServiceRules.java",
        'if (chain.failure == null) chain.failureKind = result.kind();',
        'if (chain.failure == null) chain.failureKind = com.telamin.mongoose.control.event.FailureKind.FAILED_EFFECT;', False)]),
    ("detach-retry-marked-leftover", [(agent,
        'boolean leftover = leftoverCaptures.contains(id) || !captureDetachFailures.contains(id);',
        'boolean leftover = true;', False)]),
    ("replay-mismatch-prefix-dropped", [(agent, 'com.telamin.mongoose.replay.ReplayRouting.MISMATCH + ', '', True)]),
    ("driver-rule-failure-relabelled", [(driver, 'complete(request, FailureKind.FAILED_RULE, "control processor threw: " + t);',
        'complete(request, FailureKind.FAILED_EFFECT, "control processor threw: " + t);', True)]),
    ("driver-fallback-relabelled", [(driver,
        'complete(request, FailureKind.FAILED_DRIVER_FALLBACK, "no reply after its effects settled");',
        'complete(request, FailureKind.FAILED_EFFECT, "no reply after its effects settled");', False)]),
    ("stop-result-kinds-swapped", [
        ("src/main/java/com/telamin/mongoose/StopResult.java",
         'return handedOff ? com.telamin.mongoose.control.event.FailureKind.DETACHED\n                : com.telamin.mongoose.control.event.FailureKind.FAILED_SHUTDOWN;',
         'return handedOff ? com.telamin.mongoose.control.event.FailureKind.FAILED_SHUTDOWN\n                : com.telamin.mongoose.control.event.FailureKind.DETACHED;', False)]),
    # ---- round 3 (review of 69e5ef3): R1, R2, R3, R5, A1
    ("observer-not-reported-when-attached-late", [
        ("src/main/java/com/telamin/mongoose/internal/ChronicleAuditCaptureService.java",
         'sink.useObserver(handle.writeFailureObserver);', 'sink.useObserver(null);', False)]),
    ("observer-failure-fails-install", [(agent,
        '} catch (Throwable failed) {\n            captureUnobservable.incrementAndGet();',
        '} catch (IllegalArgumentException failed) {\n            captureUnobservable.incrementAndGet();', False)]),
    ("cancel-replies-unmarked", [("src/main/java/com/telamin/mongoose/control/ControlRequest.java",
        'if (marker != null) answer = marker.apply(answer);', '', False)]),
    ("server-replies-unmarked", [("src/main/java/com/telamin/mongoose/MongooseServer.java",
        'return runner != null && runner.group().control().audit().evidenceDegraded() ? reply.unverified() : reply;',
        'return reply;', False)]),
    ("dead-control-plane-not-fenced", [(agent, 'fenceDeadControlPlane();\n                idleCyclesCounter.increment();\n                return 0;',
        'idleCyclesCounter.increment();', False)]),
    ("contain-policy-exits", [("src/main/java/com/telamin/mongoose/dutycycle/GlobalErrorHandler.java",
        'if (policy == FatalErrorPolicy.EXIT_PROCESS) {', 'if (policy != null) {', False)]),
    ("opaque-thrower-not-captured", [("src/main/java/com/telamin/mongoose/dispatch/ProcessorContext.java",
        'FAILED.set(new Failure(processor, position));', '', False)]),
    # ---- round 4 (review of df11115): the three surviving extra mutants, plus the round-4 fixes
    ("report-on-attach-removed", [("src/main/java/com/telamin/mongoose/internal/ChronicleAuditCaptureService.java",
        'if (sessionCaptureFailures > 0 && !sessionFailureReported) reportFirstFailure();', '', False)]),
    ("sink-catches-runtime-only", [("src/main/java/com/telamin/mongoose/control/ControlAuditSink.java",
        '} catch (VirtualMachineError fatal) {\n            throw fatal;\n        } catch (Throwable e) {',
        '} catch (RuntimeException e) {', False)]),
    ("heldnames-dead-branch-removed", [("src/main/java/com/telamin/mongoose/MongooseServer.java",
        '} else if (group.control().state() == com.telamin.mongoose.control.ControlDriver.GroupState.DEAD) {',
        '} else if (reservation.group() == null) {', False)]),
    ("failed-boot-not-stopped", [("src/main/java/com/telamin/mongoose/internal/ServerConfigurator.java",
        'stopped = mongooseServer.shutdown();', 'stopped = null;', False)]),
    ("service-group-health-always-up", [("src/main/java/com/telamin/mongoose/MongooseServer.java",
        'if (!ended) return com.telamin.mongoose.service.health.HealthStatus.up();',
        'if (agentGroup != null) return com.telamin.mongoose.service.health.HealthStatus.up();', False)]),
    ("contain-log-not-rate-limited", [("src/main/java/com/telamin/mongoose/dutycycle/GlobalErrorHandler.java",
        'if (state[0] != Long.MIN_VALUE && now - state[0] < REPEAT_LOG_INTERVAL_MILLIS) {', 'if (false) {', False)]),
    # ---- external review of 2fa536c
    ("opaque-progress-ignored-and-redispatched", [
        ("src/main/java/com/telamin/mongoose/dutycycle/EventQueueToEventProcessorAgent.java",
         'at = captured.position();\n                                failed = thrower;', '', False),
        ("src/main/java/com/telamin/mongoose/dutycycle/EventQueueToEventProcessorAgent.java",
         'done = true;\n                                continue;', 'from = 0; failingAt = -1; attempt = 0;\n                                continue;', False)]),
    ("capture-ack-hides-later-close-failure", [(agent,
        'if (permit.closeFailure != null && permit.closeFailureSeq > acked) closes++;',
        'if (permit.closeFailure != null && false) closes++;', False)]),
    ("i20-applied-fallback-unchecked", [("src/test/java/com/telamin/mongoose/control/validation/ControlInvariants.java",
        '} else if (fallbackReply && "APPLIED".equals(r.type()) && kind != FailureKind.NONE) {',
        '} else if (false) {', False)]),
    ("incomplete-boot-cleanup-untyped", [("src/main/java/com/telamin/mongoose/internal/ServerConfigurator.java",
        'if (stopped != null && stopped.complete()) {', 'if (true) {', False)]),
    # ---- external re-verification of 9c95864
    ("identity-only-handwritten-redelivers", [("src/main/java/com/telamin/mongoose/dutycycle/EventQueueToEventProcessorAgent.java",
        '                            solo = identified;\n', '', False)]),
    ("service-up-depends-on-group-check", [("src/main/java/com/telamin/mongoose/MongooseServer.java",
        '                        for (String hosted : group.hostedServiceNames()) flipServiceUp(hosted, 0);\n', '', False)]),
    ("replay-identity-kind-dropped", [(replayer,
        'failed = "no stream was recorded for " + cursor.name;    // loud, never an instantly complete cursor\n'
        '                    failedKind = FailureKind.REPLAY_IDENTITY_MISMATCH;',
        'failed = "no stream was recorded for " + cursor.name;    // loud, never an instantly complete cursor\n'
        '                    failedKind = FailureKind.REPLAY_STOPPED;', False)]),
]
tests = ",".join(["ControlScenarioHarnessTest", "IndependentEvidenceMutationTest", "FailureKindEvidenceMutationTest",
                  "FailureKindFlowTest", "FailureKindQuarantineTest", "CaptureAsEffectsTest",
                  "GeneratedProcessorFailureIsolationTest", "ReplayIdentityTest", "IndependentReviewReplayProbesTest",
                  "ControlTerminationAndAdmissionTest", "BoundedShutdownTest", "Review77ProbesTest",
                  "Review77bProbesTest", "GroupContainmentTest", "AuditFailurePolicyTest",
                  "OpaqueDispatchHandwrittenFailureTest", "Review77cProbesTest", "CaptureMigrationAcceptanceTest",
                  "AdminStopCommandValidationTest", "OpaquePeerRedeliveryTest", "OpaqueHandwrittenPeerTest",
                  "CaptureAcknowledgementTest", "FallbackKindInvariantTest", "FailedBootContainmentTest",
                  "IdentityOnlyRecoveryFollowupTest", "IdentityOnlyRecoveryFollowupOwnTest",
                  "ServiceHealthObservationFollowupTest", "ServiceHealthObservationFollowupOwnTest"])
cmd = ["mvn", "-o", "-Dtest=" + tests, "-Dmongoose.harness.seedStart=12001", "-Dmongoose.harness.seedCount=100", "test"]
results = []


def run(name):
    start = time.time()
    with (base / (name + ".log")).open("w") as log:
        proc = subprocess.run(cmd, cwd=repo, stdout=log, stderr=subprocess.STDOUT)
    counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    failing = set()
    for path in (repo / "target/surefire-reports").glob("TEST-*.xml"):
        if path.stat().st_mtime < start:
            continue
        xml = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(xml.attrib.get(key, 0))
        for case in xml.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                failing.add(case.attrib.get("classname", "").rsplit(".", 1)[-1])
    result = dict(name=name, exitCode=proc.returncode, caughtBy=sorted(failing), **counts)
    results.append(result)
    (base / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    print(json.dumps(result), flush=True)
    return result


print("Scratch and preserved logs: " + str(base), flush=True)
baseline = run("baseline")
assert baseline["exitCode"] == 0 and baseline["tests"] > 0, baseline
only = set(sys.argv[3].split(",")) if len(sys.argv) > 3 else None   # optional: re-run the named mutants only
for name, edits in mutations:
    if only is not None and name not in only:
        continue
    originals = {}
    try:
        for relative, before, after, replace_all in edits:
            path = repo / relative
            text = originals.setdefault(path, path.read_text()) if path not in originals else path.read_text()
            count = text.count(before)
            assert count >= 1 if replace_all else count == 1, (name, relative, "mutation anchor changed", count)
            path.write_text(text.replace(before, after))
        result = run(name)
        log_text = (base / (name + ".log")).read_text()
        assert "COMPILATION ERROR" not in log_text, (name, "mutant did not compile")
        # a mutant that exits the test JVM (System.exit) is caught by the forked-VM crash, which writes no report
        crashed = "The forked VM terminated without properly saying goodbye" in log_text
        if crashed:
            result["crashedJvm"] = True
            (base / "results.json").write_text(json.dumps(results, indent=2) + "\n")
        assert result["exitCode"] != 0 and (result["failures"] + result["errors"] > 0 or crashed), result
    finally:
        for path, text in originals.items():
            path.write_text(text)
print("All mutants rejected by JUnit assertions; scratch sources restored.", flush=True)
