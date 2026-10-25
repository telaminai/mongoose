# Unpinned macOS throughput rerun

Core candidate 49246452e5eb9879529880f264150c094547ad24 (runtime 1.1.3) versus baseline
74ad868249dc025c48aebd0a70c967542eb1dd6e (runtime 1.1.2), Java 21, same machine. Three
alternating fork pairs, two warm-up and seven measured rounds of two million events, BusySpin
feed and processor agents. No audit is enabled in this benchmark. This does not measure GC or latency.

Both use the same archived benchmark source. Compared with the earlier invalid run, the in-flight
credit limit is reduced from 4096 to 512 (below the subscriber queue capacity) and a 30-second
per-round delivery deadline prevents hanging on a lost event. This is a changed benchmark method;
its numbers must not be directly compared to historical 4096-credit runs.

All six processes completed without delivery timeout. Fork median throughput, million events/sec:

| Pair | Baseline | Candidate |
|---|---:|---:|
| 1 | 26.907 | 25.624 |
| 2 | 12.855 | 26.871 |
| 3 | 26.695 | 17.407 |

The median of fork medians is 26.695 versus 25.624 (about 4.0% lower), but the large variation
between forks prevents attributing that difference to the code. No claim of a stable regression
or improvement is supported. Pinned-core Linux qualification remains open. Logs, results, source
and runner are retained; the scratch paths in the runner identify the actual local checkouts.
