#!/usr/bin/env python3
"""Reproduce the source mutations against a fresh scratch clone; never edit the supplied repo.
Usage: JAVA_HOME=/path/to/jdk21 python3 run-source-mutations.py /path/to/mongoose
Requires offline Maven dependencies. Each mutant must compile and fail JUnit assertions.
"""
import json, os, pathlib, subprocess, sys, tempfile, time, xml.etree.ElementTree as ET
SHA = "761060947f6087437ccfff2e4dde21f41f7e77ba"
base = pathlib.Path(tempfile.mkdtemp(prefix="mongoose-source-mutations-"))
repo = base / "repo"
subprocess.run(["git", "clone", "--no-hardlinks", "--quiet", sys.argv[1], str(repo)], check=True)
subprocess.run(["git", "checkout", "--quiet", "--detach", SHA], cwd=repo, check=True)
node = "src/main/java/com/telamin/mongoose/control/node/"
driver = "src/main/java/com/telamin/mongoose/control/ControlDriver.java"
mutations = [
 ("missing-stop-reply", node+"LifecycleRules.java",
  'decide("stopComplete", result.generation(), result.name(), "Released", "Released", reply);',
  'decide("stopComplete", result.generation(), result.name(), "Released", "Released");'),
 ("applied-without-release-renamed-rule", node+"LifecycleRules.java",
  'decide("stopStarted", reg.generation, reg.name, "Started", "Releasing", pendingCleanup,\n                        requested.toArray(new ControlEffect[0]));',
  'decide("innocentLookingLabel", reg.generation, reg.name, "Started", "Releasing", reply(req, reg.generation, reg.name, ReplyOutcome.APPLIED, "stopped"));'),
 ("missing-stale-open-close", node+"CaptureRules.java",
  'decideCompletion("captureOpenedStale", open.generation, name, c.resourceId(), note("stale", true),\n                closeEffect(root, open.generation, name, c.resourceId()));',
  'decideCompletion("captureOpenedStale", open.generation, name, c.resourceId(), note("stale", true));'),
 ("skip-terminal-sweep", driver,
  'if (r.isClaimed()) continue;',
  'if (r.isClaimed() || r.requestId() % 5 == 0) continue;'),
 ("drop-claimed-stop", driver,
  'claimed.put(request.requestId(), request);',
  'if (request.kind() == ControlRequest.Kind.STOP && request.requestId() % 7 == 0) return;\n        claimed.put(request.requestId(), request);'),
]
cmd = ["mvn", "-o", "-Dtest=ControlScenarioHarnessTest,IndependentEvidenceMutationTest",
       "-Dmongoose.harness.seedStart=12001", "-Dmongoose.harness.seedCount=20", "test"]
results = []
def run(name):
    start = time.time()
    with (base / (name+".log")).open("w") as log:
        proc = subprocess.run(cmd, cwd=repo, stdout=log, stderr=subprocess.STDOUT)
    counts = {"tests":0,"failures":0,"errors":0,"skipped":0}
    for path in (repo/"target/surefire-reports").glob("TEST-*.xml"):
        if path.stat().st_mtime < start: continue
        xml = ET.parse(path).getroot()
        for key in counts: counts[key] += int(xml.attrib.get(key,0))
    result = dict(name=name, exitCode=proc.returncode, **counts)
    results.append(result)
    (base/"results.json").write_text(json.dumps(results,indent=2)+"\n")
    print(json.dumps(result), flush=True)
    return result
print("Scratch and preserved logs: "+str(base), flush=True)
baseline=run("baseline")
assert baseline["exitCode"] == 0 and baseline["tests"] > 0, baseline
for name, relative, before, after in mutations:
    path = repo/relative
    original=path.read_text()
    assert original.count(before) == 1, (name,"mutation anchor changed")
    try:
        path.write_text(original.replace(before,after))
        result=run(name)
        assert result["exitCode"] != 0 and result["failures"] > 0 and result["errors"] == 0, result
    finally:
        path.write_text(original)
print("All five mutants rejected by JUnit assertions; scratch runtime sources restored.",flush=True)
