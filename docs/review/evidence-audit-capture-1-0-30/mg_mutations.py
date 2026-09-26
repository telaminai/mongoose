"""Mutation controls for mongoose develop 2c4192e. Each: green baseline of the named test; mutate from a BYTE COPY;
run the named test; require a <failure> (not <error>) at it; restore from the byte copy; cmp; green again."""
import subprocess, glob, os, sys, shutil, filecmp, re, xml.etree.ElementTree as ET
ROOT = os.getcwd(); REP = os.path.join(ROOT, 'target', 'surefire-reports')
CAP = 'src/main/java/com/telamin/mongoose/internal/ChronicleAuditCaptureService.java'
DIR = 'src/main/java/com/telamin/mongoose/internal/DirAuditIntrospectionService.java'
M = [
 ("4e3dba7 fan-out: the configured listener no longer receives while recording", CAP,
  "            LogRecordListener delegate = previousListener;", "            LogRecordListener delegate = null;",
  "AuditCaptureFanOutTest#recordsReachBothDestinationsAndTheListenerIsRestoredOnStop"),
 ("4e3dba7 restore: stop installs a no-op again", CAP,
  "                        previousListener != null ? previousListener : NoOpLogRecordListener.INSTANCE);",
  "                        NoOpLogRecordListener.INSTANCE);",
  "AuditCaptureFanOutTest#recordsReachBothDestinationsAndTheListenerIsRestoredOnStop"),
 ("35f9a13 adopt: a re-attached flow is not adopted while recording", CAP,
  "            if (captureListener != null && dataFlow != null) {\n                dataFlow.setAuditLogProcessor(captureListener);",
  "            if (false) {\n                dataFlow.setAuditLogProcessor(captureListener);",
  "AuditCaptureFanOutTest#recordsFromAReAttachedProcessorReachTheCaptureQueue"),
 ("2c4192e hook: live-sink mutations are not reported to the listing", DIR,
  "        captureService.onLiveSinkMutation(this::invalidate);", "        // hook removed (mutation)",
  "AuditListingFreshnessTest#aSinkThatStartsAfterTheFirstListingAppears"),
 ("2c4192e overlay: the cached snapshot is served as-is", DIR,
  "        return withLiveCounters(cached);", "        return cached;",
  "AuditListingFreshnessTest#theListedRecordCountAdvancesWithTheFile"),
]
def run(test):
    for p in glob.glob(os.path.join(REP, 'TEST-*.xml')): os.remove(p)
    r = subprocess.run(['mvn', '-B', '-q', '-o', 'test', '-Dtest=' + test, '-Dsurefire.failIfNoSpecifiedTests=false'],
                       cwd=ROOT, capture_output=True, text=True)
    res = {}
    for p in glob.glob(os.path.join(REP, 'TEST-*.xml')):
        for tc in ET.parse(p).getroot().iter('testcase'):
            k = tc.get('classname').split('.')[-1] + '#' + re.sub(r'\(.*\)$', '', tc.get('name'))
            f, e = tc.find('failure'), tc.find('error')
            res[k] = ('failure', f.get('message') or '') if f is not None else ('error', e.get('message') or '') if e is not None else ('pass', '')
    return 'COMPILATION ERROR' not in r.stdout + r.stderr, res
ok = True
for name, rel, old, new, named in M:
    p = os.path.join(ROOT, rel); copy = p + '.review-copy'; shutil.copyfile(p, copy)
    c0, base = run(named); baseline = c0 and base.get(named, ('missing',))[0] == 'pass'
    s = open(p, encoding='utf-8').read()
    if s.count(old) != 1: print("BAD anchor", name, s.count(old)); ok = False; os.remove(copy); continue
    open(p, 'w', encoding='utf-8').write(s.replace(old, new))
    try: c1, res = run(named); kind, msg = res.get(named, ('missing', ''))
    finally: shutil.copyfile(copy, p)
    same = filecmp.cmp(p, copy, shallow=False); os.remove(copy)
    c2, again = run(named); green = c2 and again.get(named, ('missing',))[0] == 'pass'
    good = baseline and c1 and kind == 'failure' and same and green; ok &= good
    print(f"{'OK ' if good else 'BAD'} {name}: baseline={'green' if baseline else 'NOT'} compiled={c1} "
          f"{named.split('#')[1]}->{kind} '{msg[:110]}' cmp-identical={same} green-after={green}")
print("ALL HOLD" if ok else "SOMETHING DID NOT HOLD")
