import hashlib,json,pathlib,re,subprocess,time
out=pathlib.Path('/private/tmp/mongoose-rc1-final-4924645/benchmark')
out.mkdir(exist_ok=True)
root=pathlib.Path('/private/tmp/mongoose-pr76-lead')
base=pathlib.Path('/private/tmp/mongoose-pr76-perf-base')
cp=pathlib.Path('/private/tmp/pr76-benchmark-classpath.txt').read_text()
java='/Users/greg/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home/bin/java'
flags=[]
for module in ['jdk.internal.misc','java.lang.reflect','java.io','java.nio','sun.nio.ch','jdk.internal.ref','jdk.internal.util']:
 flags+=['--add-opens','java.base/'+module+'=ALL-UNNAMED']
results=[]
for i in range(3):
 for label,repo in ([('base',base),('candidate',root)] if i%2==0 else [('candidate',root),('base',base)]):
  classpath=(str(root/'target/test-classes')+':'+str(root/'target/classes')+':'+pathlib.Path('/private/tmp/mongoose-rc1-final-72b6fd4/candidate-classpath.txt').read_text().strip()) if label=='candidate' else cp.replace(str(root),str(repo))
  classes=out/('classes-'+label)
  if not classes.exists():
   classes.mkdir()
   subprocess.run([java.replace('/java','/javac'),'-cp',classpath,'-d',str(classes),str(out/'ControlPlaneCostBenchmark.java')],check=True)
  classpath=str(classes)+':'+classpath
  log=out/f'benchmark-{i+1}-{label}.log'
  with log.open('w') as f:
   proc=subprocess.run([java,*flags,'-cp',classpath,'com.telamin.mongoose.benchmark.ControlPlaneCostBenchmark',label,'throughputOnly'],cwd=repo,stdout=f,stderr=subprocess.STDOUT,timeout=90)
  data=log.read_text();match=re.search(r'throughput_Mev/s median=([0-9.]+) min=([0-9.]+) max=([0-9.]+)',data)
  row=dict(pair=i+1,build=label,exit=proc.returncode,result=list(map(float,match.groups())) if match else None)
  results.append(row);out.joinpath('benchmark.json').write_text(json.dumps(results,indent=2));print(json.dumps(row),flush=True)
  if proc.returncode or not match:raise SystemExit(1)
  time.sleep(1)
