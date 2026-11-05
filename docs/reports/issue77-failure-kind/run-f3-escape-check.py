#!/usr/bin/env python3
"""Review 77 F3: the kind-keyed harness must catch everything the dfbfdd9 reason-text harness caught.

Usage: JAVA_HOME=/path/to/jdk21 TMPDIR=/scratch python3 -I run-f3-escape-check.py /path/to/mongoose <old-sha> <new-sha> [seeds]

Applies the reviewer's mutant to both commits in fresh scratch clones: at stopComplete, a stop of an even generation is
answered FAILED (kind FAILED_EFFECT where the kind exists), reason "spurious release failure", although the release
completed. Runs ControlScenarioHarnessTest over the same seeds at both commits and fails if any seed caught at the old
commit escapes at the new one. Never edits the supplied repo.
"""
import json, pathlib, subprocess, sys, tempfile, xml.etree.ElementTree as ET

repo_url, old_sha, new_sha = sys.argv[1], sys.argv[2], sys.argv[3]
seeds = int(sys.argv[4]) if len(sys.argv) > 4 else 200
base = pathlib.Path(tempfile.mkdtemp(prefix="mongoose-f3-escape-"))
rules = "src/main/java/com/telamin/mongoose/control/node/LifecycleRules.java"
OLD_BEFORE = '''                ControlEffect reply = reply(req, result.generation(), result.name(),
                        chain.failure == null ? ReplyOutcome.APPLIED : ReplyOutcome.FAILED,
                        chain.failure == null ? "stopped" : chain.failure);'''
OLD_AFTER = '''                boolean spurious = chain.failure == null && result.generation() % 2 == 0;
                ControlEffect reply = reply(req, result.generation(), result.name(),
                        chain.failure == null && !spurious ? ReplyOutcome.APPLIED : ReplyOutcome.FAILED,
                        spurious ? "spurious release failure" : chain.failure == null ? "stopped" : chain.failure);'''
NEW_BEFORE = '''                ControlEffect reply = reply(req, result.generation(), result.name(),
                        chain.failure == null ? ReplyOutcome.APPLIED : ReplyOutcome.FAILED, failureKindOf(chain),
                        chain.failure == null ? "stopped" : chain.failure);'''
NEW_AFTER = '''                boolean spurious = chain.failure == null && result.generation() % 2 == 0;
                ControlEffect reply = reply(req, result.generation(), result.name(),
                        chain.failure == null && !spurious ? ReplyOutcome.APPLIED : ReplyOutcome.FAILED,
                        spurious ? FailureKind.FAILED_EFFECT : failureKindOf(chain),
                        spurious ? "spurious release failure" : chain.failure == null ? "stopped" : chain.failure);'''


def caught(sha, before, after):
    repo = base / sha
    subprocess.run(["git", "clone", "--no-hardlinks", "--quiet", repo_url, str(repo)], check=True)
    subprocess.run(["git", "checkout", "--quiet", "--detach", sha], cwd=repo, check=True)
    path = repo / rules
    text = path.read_text()
    assert text.count(before) == 1, (sha, "mutation anchor changed")
    path.write_text(text.replace(before, after))
    with (base / (sha + ".log")).open("w") as log:
        subprocess.run(["mvn", "-o", "-Dtest=ControlScenarioHarnessTest#generatedScenarioKeepsLedgerAndAuditInvariants",
                        "-Dmongoose.harness.seedStart=1", "-Dmongoose.harness.seedCount=" + str(seeds), "test"],
                       cwd=repo, stdout=log, stderr=subprocess.STDOUT)
    assert "COMPILATION ERROR" not in (base / (sha + ".log")).read_text(), sha
    failing, total = set(), 0
    for report in (repo / "target/surefire-reports").glob("TEST-*.xml"):
        for case in ET.parse(report).getroot().iter("testcase"):
            total += 1
            if case.find("failure") is not None or case.find("error") is not None:
                failing.add(case.attrib["name"])
    return failing, total


print("scratch: " + str(base), flush=True)
old, old_total = caught(old_sha, OLD_BEFORE, OLD_AFTER)
new, new_total = caught(new_sha, NEW_BEFORE, NEW_AFTER)
escapes = sorted(old - new)
result = dict(seeds=seeds, oldSha=old_sha, newSha=new_sha, oldCases=old_total, newCases=new_total,
              oldCaught=len(old), newCaught=len(new), caughtOnlyByNew=len(new - old), escapes=escapes)
(base / "f3-result.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result), flush=True)
assert not escapes, "seeds caught at the old commit escape at the new one: " + str(escapes)
