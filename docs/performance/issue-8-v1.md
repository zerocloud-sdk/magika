# Issue #8 performance and resource report — v1

Scope: [#8](https://github.com/zerocloud-sdk/magika/issues/8), under
[parent #1](https://github.com/zerocloud-sdk/magika/issues/1). Report date:
2026-09-17. Fixed implementation/review base:
`5fbbeabf64041ffe4c4ef5d196eba20ddf85ded5`.

The standalone [measurement tool](../../benchmarks/README.md) exercises the
installed public SDK; production SDK sources, dependencies, assets, tests and
resource bounds are unchanged. SDK 0.1.0, native ORT 1.30.0, model standard_v3_3,
upstream `9f225aa480e675af44343b9160f077073ed1b752` remain pinned. The consumed SDK
JAR SHA-256 is `0b6100573efff99e49b2c2288a820a72bcac25e9ce09d17c47cf4f029df27ca3`.
The original model SHA-256 is
`fe2d2eb49c5f88a9e0a6c048e15d6ffdf86235519c2afc535044de433169ec8c`.
Runtime applies the existing stable LayerNorm reduction layout after asset
validation, with sequential graph execution and NO_OPT. The digest identifies
the bundled upstream asset; source fingerprints identify its runtime adaptation.
No graph optimization or numerical/resource tolerance was changed for these numbers.

## Reproduction

Run as a normal user on Ubuntu 24.04 x64/CPU. Tests and performance measurements
must run sequentially, with no other high-load work competing for the host:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
receipt=$(mktemp -d /tmp/magika-issue8-verification.XXXXXXXX)
for runtime in 8 17 21; do
  mvn -B -ntp -Dtest.java.home="/usr/lib/jvm/java-$runtime-openjdk-amd64" clean install \
    > "$receipt/java-$runtime.log" 2>&1 || exit 1
  mkdir -p "$receipt/java-$runtime"
  cp -R target/surefire-reports target/failsafe-reports "$receipt/java-$runtime/"
done
mvn -B -ntp -f examples/offline/pom.xml clean package
for runtime in 8 17 21; do
  bash scripts/verify-offline.sh "/usr/lib/jvm/java-$runtime-openjdk-amd64"
done
mvn -B -ntp -f benchmarks/pom.xml clean package
bash benchmarks/run.sh /tmp/magika-performance-v1 all
```

Use a fresh output directory on reruns. Replace `all` with `path`, `stream`,
`batch`, `shared` or `resources` to run one scenario. Exact expanded JVM commands,
versions, configuration and timing/resource definitions accompany the raw run and
[tool documentation](../../benchmarks/README.md).

## Corpus and method

All 69 original upstream files in the pinned path manifest, in manifest order:
1,490,089 bytes total; sizes 0–292,305 bytes; median 607 bytes. Each pass has
68 model results, one empty-content rule result and zero expected failures.
All files are authenticated before measurement, and every result is consumed and
compared to a fixed per-file sequential oracle (all fields, exact single-call
scores, existing 1e-5 batch tolerance). The fixed oracle is not an accuracy proof.
The SDK reference suites separately verify compatibility with upstream results.

Each performance JVM initializes one SDK before warmup and reuses it. The default
run has five warmup and ten measured rounds, each repeating the corpus ten times:
690 inputs/round, 3,450 warmup and 6,900 measured inputs/scenario. HIGH_CONFIDENCE,
batchSize 32, intraOpThreads 1, stream limit 64 MiB. Path/stream/batch have one
caller; shared has four application workers on one instance, dividing the same
690 positions by stride. Timed workloads are closed-loop, warm-cache local files;
streams include caller file open, complete consumption, EOF/ownership check and
close. This is not an HTTP upload, cold-disk, initialization or queued-service test.

Every JVM uses `-Xms128m -Xmx128m -XX:+AlwaysPreTouch -XX:+UseSerialGC
-XX:ActiveProcessorCount=4`. Throughput is total measured inputs divided by summed
round wall seconds, including validation/control work. Per-request latency ends
before result validation; batch calls include validating callbacks. Batch item
latency starts when the iterator supplies that item and ends at its callback
entry. Batch amortized time divides full call time by delivered items. Those
three batch metrics have different meanings and are reported separately.

Durations are recorded as integer nanoseconds. Summary means are arithmetic;
percentiles pool measured samples and use exact nearest rank. Round output and
resource observation happen outside timing. The harness keeps finite timing
arrays, capped at one million measured scalars, and a 69-file oracle, rather than
retaining every request result. Unlimited-input memory claims come from the
separate existing bounded-source probes, not from the timing arrays.

## Executed environment and performance

Recorded run `performance-final-v1`, 2026-09-17 UTC: Ubuntu 24.04.4 LTS, Linux
6.8.0-136-generic x86_64, KVM guest reporting four Intel Xeon Skylake (IBRS)
vCPUs (one socket, two cores, two threads/core), 8,326,942,720 bytes guest RAM,
XFS on `/dev/vda2`. Compiler and measurement JVM: Ubuntu OpenJDK
21.0.11+10-1-24.04.2-Ubuntu. The full environment snapshot includes memory/swap
occupancy and virtual CPU details; CPU frequency/affinity and hypervisor load
were not controlled. The cgroup filesystem exposed cpuset 0–3, but no `cpu.max` or
`memory.max` file at the paths queried. No SDK tests or other deliberately
started high-load validation ran alongside these measurements.

Each scenario has **6,900 measured inputs: 6,800 model results, 100 rule results,
zero failures**. Each also completed 3,450 warmup inputs. Results below derive
from its `summary.json`, all individual `latencies.csv` rows and `rounds.csv`.
Times in the following table are **milliseconds**, throughput is **items/second**:

| Scenario | Throughput | Latency definition | Mean ms | p50 ms | p95 ms | p99 ms | Samples |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| Path, one caller | 86.16 | Request | 11.600 | 11.682 | 12.450 | 12.807 | 6,900 |
| InputStream, one caller | 86.66 | Request | 11.532 | 11.624 | 12.322 | 12.767 | 6,900 |
| identifyAll, batchSize 32 | 69.81 | Input-to-callback | 452.935 | 456.858 | 483.732 | 498.116 | 6,900 |
| Shared Path, four callers | 312.42 | Request | 12.386 | 12.287 | 13.636 | 20.625 | 6,900 |

One entire `identifyAll` call delivers 690 inputs in multiple internal batches.
Across **10 calls**, complete-call mean / p50 / p95 / p99 were
**9.884368 / 9.874070 / 10.075684 / 10.075684 seconds**.
With only ten whole-call samples, p95 and p99 both select the maximum observed
call. **Amortized batch time was 14.325 ms/item**;
this is distinct from the approximately 452.9 ms mean input-to-callback wait.

In this environment, batchSize 32 did not improve throughput over single Path
calls. Four concurrent callers increased aggregate throughput while individual
requests still took roughly 12 ms on average. These are observations for this
corpus and the existing compatibility configuration, not a kernel-level diagnosis
or a general batch/concurrency sizing recommendation. No compatibility tradeoff
was made to improve the numbers. Each scenario uses one fresh JVM; the ten rounds
are observations within that JVM, not ten independent process forks or a
confidence interval for future production traffic.

## Heap and process observations during performance runs

The MemoryMXBean heap committed/max values were **129,761,280 bytes** in these
JVMs configured with `-Xms128m -Xmx128m`. Heap values below are **MiB**; RSS values
are **KiB** from Linux `/proc/self/status`. Ranges use the ten points immediately
after measured rounds, not continuous sampling or peak RSS. No explicit GC was
requested in the performance runs. GC counts/time, nonheap usage, anonymous/file
RSS and every stage are retained in each `resources.csv`.

| Scenario | Heap used at end of warmup, MiB | Measured-point heap range, MiB | RSS after warmup, KiB | Measured-point RSS range, KiB | RSS after SDK close, KiB | fd: warmup / measured range / close |
| --- | ---: | --- | ---: | --- | ---: | --- |
| path | 11.144 | 5.797–36.942 | 250604 | 250652–250824 | 242152 | 20 / 20–20 / 18 |
| stream | 6.428 | 8.530–38.057 | 263836 | 263920–264028 | 255356 | 20 / 20–20 / 18 |
| batch | 7.371 | 8.696–38.106 | 492700 | 493168–504048 | 241140 | 20 / 20–20 / 18 |
| shared | 14.902 | 7.316–38.424 | 292080 | 292104–292544 | 259480 | 20 / 20–20 / 18 |

RSS includes resident Java heap, JVM native structures, JIT, stacks, shared
libraries, ORT and allocator caches. It is **not precise ORT native allocation**;
subtracting Java heap usage would not isolate ORT either. Observations include
the harness and its timing storage. SDK close does not unload JVM/ORT shared
infrastructure; the shared scenario still has idle application workers at its
last sample, before executor shutdown. Batch RSS rose while the Session was live
and fell substantially at close; the repeated-lifetime experiment below provides
the separate longer-term trend. No exact-RSS-equality requirement is applied.

## Steady resource experiment

The separate `resources` JVM completed **30 warmup + 200 measured cycles** in
**314.379 seconds**, with eight equal stages of 25 measured cycles. Each cycle
creates an SDK with **intraOpThreads=2**, performs successful Path, stream and
mixed-batch calls, missing-file and stream IOException failures, callback and
iterator aborts, and interruption. It verifies recovery, exits exceptionally
through try-with-resources, closes again, verifies a retained result and checks
closed-instance rejection. All calls use public SDK APIs and actual native
inference. BatchSize is 32, HIGH_CONFIDENCE and the default 64 MiB stream limit.

Each cycle asserts **73 observed model results, 16 rule results, 18 input
failures, three batch aborts and one exceptional body exit**. Across all 230
cycles this is 16,790 model results, 3,680 rule results, 4,140 input failures,
690 aborts and 230 exceptional exits, in addition to initial oracle construction.
Some native rows computed before abort are not delivered; model-result counts
only include returned/delivered values. No cycle result history is retained.
The existing native fault/lifecycle probes below complement these repeated
public input/callback failures.

Sampling is after all instances in a stage close, before and after an explicit
`System.gc()` request. Actual GC counters are recorded, so the second observation
is described as after a request rather than assumed to be an exact live-object
census. MemoryMXBean supplies heap data; `/proc/self/status` supplies RSS,
RssAnon/RssFile; `/proc/self/fd` supplies descriptor totals. The sampler's own
output/directory descriptors are included. These are whole-process observations,
not a measurement of isolated ORT allocation. The resident fixed heap helps keep
heap expansion separate from the RSS trend.

| Stage | Completed cycles | Elapsed s (after GC request) | Heap before / after GC request, MiB | RSS before / after GC request, KiB | fd before / after |
| --- | ---: | ---: | --- | --- | --- |
| warmup | 30 | 42.178 | 69.260 / 2.534 | 292780 / 293016 | 18 / 18 |
| phase_1 | 55 | 76.279 | 77.110 / 2.538 | 295224 / 295228 | 18 / 18 |
| phase_2 | 80 | 109.807 | 77.105 / 2.540 | 297720 / 297724 | 18 / 18 |
| phase_3 | 105 | 143.967 | 77.101 / 2.484 | 299264 / 299264 | 18 / 18 |
| phase_4 | 130 | 177.770 | 76.383 / 2.482 | 299616 / 299628 | 18 / 18 |
| phase_5 | 155 | 212.148 | 76.378 / 2.482 | 299696 / 299696 | 18 / 18 |
| phase_6 | 180 | 246.248 | 76.366 / 2.482 | 299696 / 299696 | 18 / 18 |
| phase_7 | 205 | 280.543 | 77.046 / 2.484 | 299696 / 299696 | 18 / 18 |
| phase_8 | 230 | 314.379 | 77.044 / 2.484 | 299716 / 299716 | 18 / 18 |

**Observed conclusion:** every post-warmup descriptor count was 18. After-GC-request
heap remained within 2.482–2.540 MiB. RSS rose from 293,016 KiB after warmup to
299,716 KiB at the final sample, a difference of 6,700 KiB. Its growth slowed:
the final 100 cycles (phase 4 through phase 8) changed RSS by only 88 KiB, with
phases 5–7 all at 299,696 KiB. The recorded trend shows stable heap/descriptor
observations and RSS approaching a plateau. No production resource defect was
identified, so no SDK fix or assertion relaxation was made. This is evidence
for the recorded duration and workload, not a proof that every possible
application lifetime is leak-free. No exact-RSS-equality or QPS/latency threshold
is imposed.

## Full verification results

Every full build used JDK 21.0.11 to compile with `--release 8`. Surefire and
Failsafe XML record the actual runtime below. All runs have zero failures,
errors or skips. Strict Javadoc (`doclint=all`, warnings fatal), the standalone
consumer build, and all three Java-only network-isolated consumers passed.
Permission denial checks ran as uid 1000 and observed actual denied file opens.

| Actual test JVM | Unit tests | Packaged integration tests | Strict Javadoc | Offline consumer |
| --- | ---: | ---: | --- | --- |
| 1.8.0_492-8u492-ga~us2-0ubuntu1~24.04.1-b09 | 39 | 17 | Passed | Passed |
| 17.0.19+10-1-24.04.2-Ubuntu | 39 | 17 | Passed | Passed |
| 21.0.11+10-1-24.04.2-Ubuntu | 39 | 17 | Passed | Passed |

Each runtime retains all 9,261 exact feature comparisons, 141 content references,
207 path and 207 batch reference comparisons, the stream/input/ownership and
concurrency suites, real native inference, asset/bytecode checks and failure-path
probes. The numerical and resource assertions are unchanged. All three main JARs,
the installed dependency and both independent consumers use the same SDK digest
listed above. The measurement/probe classes and Python are absent from its 45
ZIP entries. The original 124-file executable-source/resource fingerprint still
matches; only measurement tooling and documentation are changed by #8.

The full matrix's retained probes establish bounded input handling independently
of the performance harness:

- `StreamProbe`: all runtimes consumed **2,147,483,665 bytes** through EOF after
  explicitly raising `maxStreamBytes` to that length in a separate `-Xmx64m` JVM.
  Actual maximum heap was **64,487,424 bytes** on Java 8, **67,108,864 bytes** on
  17/21. All returned a real model result (`unknown`, raw `randombytes`, score
  `0.9968738555908203`, OVERWRITE_MAP), compared all fields to an 8,209-byte
  representative and preserved caller close/reset ownership. The generator
  retains a fixed 4,096-byte pattern; neither producer nor consumer saves the
  complete stream.
- `BatchProbe`: every runtime delivered **1,000,003** fresh lazy Paths with
  batchSize 32 under the same heap limits, including **977 model rows**, **245
  file failures**, **998,781 rule results**, and exactly one retained result.
  Ordered indices, bounded lookahead, summary totals and file handles passed.
  Input generation keeps only three path strings and a counter.
- `PackagedProbe` repeats 60 create/close cycles plus 6,000 reused-session
  inferences after warmup; `BatchProbe` repeats 90 success/error/abort/interrupt
  cycles after warmup. Both retain their fixed resident 128 MiB heaps, original
  96 MiB RSS tolerances and descriptor bounds. These are regression observations,
  not the throughput measurements above.
- `LifecycleOrtProbe` verifies real shared Session inference, per-call native
  resource ownership, close drain/order/once, interruption, initialization,
  inference and release failure handling, and reuse of other instances. Its
  release-failure injections occur after actual native release; they verify
  reporting/control flow, not recovery of an unreleasable native handle. See
  [the existing lifecycle record](../verification-issue-7.md) for the unchanged
  public-boundary coverage.

| JVM | Single-call RSS KiB (4 stages); fd | Batch RSS KiB (4 stages); fd | Million-path RSS KiB (5 points); fd |
| --- | --- | --- | --- |
| 8 | [211868, 224344, 226320, 227156]; 39→36 | [445316, 448464, 471332, 470892]; 36→36 | [102612, 115772, 117924, 118096, 118104]; 36→36 |
| 17 | [228212, 248020, 248092, 251532]; 22→19 | [463596, 484132, 502704, 502772]; 22→19 | [112440, 163552, 165760, 165936, 165940]; 19→19 |
| 21 | [236172, 251148, 249760, 256788]; 21→18 | [462944, 482608, 497580, 497624]; 21→18 | [115736, 161972, 164212, 164392, 164404]; 18→18 |

The single-call points are after warmup and each additional 20 creation cycles
plus 2,000 reused inferences. Batch points are after warmup and each additional
30 cycles. Million-path points are after warmup and 250k/500k/750k/1,000k
deliveries, before the final three. Descriptor pairs are the pre/post-workload
process totals. Workload and exact sample positions are in the unchanged probes.

The historical process-wide file-descriptor assertion failure from #6 was **not
reproduced** in this three-JVM matrix. Its original cause is still unknown;
passing these runs does not establish a root cause or constitute a fix. The
failure-only descriptor diagnostics and original bounds remain unchanged.

## Evidence bundle and scope

The [raw run directory](issue-8-v1/) contains expanded commands, environment,
source/artifact SHA-256 inventories, per-file corpus metadata, warmup/measured
rounds, all 27,600 item timing samples, summaries and resource points. Each
scenario's `metadata.json` hashes the SDK JAR actually loaded. The recorded
production sources match the fixed base; the measurement source hashes identify
the independently built tool. Report/tool format is **magika-performance-v1**;
the measurement Maven artifact is `net.zerocloud.benchmarks:identification:1.0.0`,
separate from SDK 0.1.0 and model standard_v3_3.

The [verification archive](issue-8-v1-verification.tar.gz) retains all three full
Maven logs, each JVM's Surefire/Failsafe XML/text, actual SDK checksums, standalone
consumer build, offline logs, measurement build, focused baseline, matrix script,
source fingerprints and independent audit. Inspect it with:

```sh
tar -tzf docs/performance/issue-8-v1-verification.tar.gz
mkdir -p /tmp/magika-issue8-receipts
tar -xzf docs/performance/issue-8-v1-verification.tar.gz -C /tmp/magika-issue8-receipts
sha256sum -c docs/performance/issue-8-v1/sources.sha256
# After rebuilding the artifacts in the reproduction commands:
sha256sum -c docs/performance/issue-8-v1/artifacts.sha256
```

An independent Python 3.12.3 standard-library audit recomputed every percentile,
mean and weighted throughput from raw CSV, checked outcome/sample counts,
resource stages, JVM XML totals and protected/source hashes. Its results are
[recorded here](issue-8-v1/audit-summary.json). Python was used only for this
independent evidence audit/report preparation; SDK, Maven tests, measurement
execution/summaries and offline consumers do not require Python.

Performance evidence applies to this guest, JVM, fixed corpus, default prediction
mode, thread counts and warm-cache closed-loop workload. Other heap/GC settings,
storage, request sizes, failure mixes, concurrency levels or CPU allocation can
change results. Only one process fork per performance scenario was measured;
round samples do not supply a cross-host or cross-fork uncertainty estimate.
Resource sampling misses between-point peaks and cannot attribute exact native
allocations. The 230-cycle trend and bounded probes cover the stated workloads,
not arbitrarily long processes. No fixed QPS/latency gate, new platform support
or all-category accuracy claim follows from this report. No release rehearsal,
tag, Release or Central publication is part of #8.

## Issue #8 acceptance mapping

| AC | Requirement | Current evidence |
| --- | --- | --- |
| 1 | Reproducible real SDK identification in all four scenarios | Independent consumer, four successful runs, 27,600 verified measured results; per-scenario raw timing/outcome files. |
| 2 | Complete configuration; distinguish throughput, latency, heap and process memory | Environment/metadata/source and artifact hashes; method definitions; separate request, batch-call, delivery and amortized metrics; MXBean and `/proc` observations. |
| 3 | Independent constrained-heap stream and lazy-path bounds | All three JVMs pass the unchanged 2 GiB + 17 stream and 1,000,003-path probes under `-Xmx64m`, bounded producer/consumer storage and full delivery. |
| 4 | Steady repeated creation/inference/error/close resource trends; fix demonstrated growth | 30 + 200 cycles with nine post-warmup phase pairs, stable descriptor/heap observations and RSS approaching a plateau; original resource/native-fault gates pass. No production defect identified. Historical fd failure remains explicitly unresolved. |
| 5 | State environment/limits without unverified promises or invented thresholds | Environment, timing/sample/corpus/VM limits above; no added performance gate or expanded compatibility/accuracy claim. |
| 6 | Regressions and inspectable versioned evidence for #10 | Full actual Java 8/17/21 tests/native inference, strict Javadoc, independent and network-isolated consumers; v1 raw run, verification archive and source/artifact hashes. No SDK fix was needed. |

## Review and delivery

The fixed review base is `5fbbeabf64041ffe4c4ef5d196eba20ddf85ded5`.
Two independent reviewers examined the actual 47-file staged change, empty
unstaged diff and complete untracked inventory. The empty base-to-HEAD commit
range before the ticket commit was not used as a replacement for that scope.
Both reviewed the final refreshed evidence without rerunning the measurements.
Their findings are recorded separately below; the final delivery response
supplies the local commit SHA and post-commit workspace receipt.

The original 24 untracked paths were inventoried and hashed before work. They
are excluded from staging, and their contents and untracked state are verified
again after commit. Test/compiler outputs are ignored in the existing target
directories or the standalone benchmark's target directory. Only #8 measurement,
report/evidence and the README link are included in this ticket.

## Standards

**0 findings: 0 documented-standard breaches and 0 actionable heuristic smells.**
The independent review applied AGENTS.md, docs/agents, CONTEXT.md, all five
accepted ADRs, documented testing boundaries and the code-review smell baseline.
The tool preserves the public SDK boundary, stream ownership and lifecycle
contracts, pinned versions and separation from production packaging. Metadata
uses public ModelInfo getters.

The reviewer independently recomputed all 27,600 timing samples, round counts,
distributions and weighted throughput; raw data, summaries, audit output and
report tables agree. Final source/JAR hashes pass, and resource observations
match the reported heap range, 18 descriptors and 88 KiB final-100-cycle RSS
change. The refreshed archive confirms actual Java 8/17/21 execution, 39 unit
and 17 integration tests per JVM, zero failures/errors/skips, successful builds
and offline consumers. All 124 original source/resource fingerprints and all
24 protected files' contents/untracked status remain unchanged.

## Spec

**0 findings: 0 missing/partial requirements, 0 scope extensions and 0
implementation errors.** The independent review found inspectable evidence for
all six #8 acceptance criteria. The 47 changed files belong to measurement
tooling, report/evidence or the README link; production SDK, tests, dependencies
and fixed assets remain unchanged. The four public SDK scenarios, result
consumption, timing boundaries, statistics and resource sampling match the spec.

The reviewer independently recomputed all 27,600 timings, throughput, means,
percentiles, batch-call times and outcome counts. All 29 source hashes and six
artifact hashes match; delivered raw files match the final run byte-for-byte,
and archived/final audits agree. The resource evidence supports the report's
limited conclusion of RSS approaching a plateau, while the historical descriptor
failure remains explicitly unresolved. Archived actual Java 8/17/21 tests,
strict Javadoc, bounded-heap probes and network-isolated consumers pass; all
124 original source/resource fingerprints and 24 protected paths are preserved.

Review totals: **Standards 0; Spec 0**. Neither axis has an unresolved finding.
