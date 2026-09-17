# Public SDK measurements (format v1)

This independent Maven consumer measures SDK `0.1.0` with real ORT `1.30.0` CPU
inference. It is not a module/dependency of the production build and adds no SDK
API. Its Java sources compile with JDK 21 and `--release 8`. Building, measuring
and writing summaries require no Python. Linux `/proc` supplies resource samples.
The [versioned report](../docs/performance/issue-8-v1.md) records an executed run.

## Run

Build as a normal user on the verified Ubuntu 24.04 x64 host. Finish all builds
and tests before timing; avoid competing workloads. Install the current SDK,
then build this consumer against that artifact:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -B -ntp clean install
mvn -B -ntp -f benchmarks/pom.xml clean package
bash benchmarks/run.sh /tmp/magika-performance-v1 all
```

The output directory must not already exist. Each scenario runs in a fresh JVM,
sequentially: `path`, `stream`, `batch`, `shared`, then a separate `resources`
workload. To reproduce just one scenario, replace `all` with its name and use a
new output directory. Failed identification, wrong fields/counts, or broken
ownership/progress checks terminate with a nonzero exit; no successful summary
is written for a failed scenario. The shell stops on the first failed process.

Defaults (all recorded in output): `WARMUP_ROUNDS=5`, `MEASUREMENT_ROUNDS=10`,
`CORPUS_REPEATS=10`, `BATCH_SIZE=32`, `INTRA_OP_THREADS=1`, `CALLERS=4`.
Set these environment variables to run another workload. `CALLERS` applies only
to `shared`; the other performance scenarios have one caller. Each round visits
all 69 files ten times (690 items total even with four callers). Prediction mode
is HIGH_CONFIDENCE, stream limit 64 MiB. ORT graph execution is sequential with
NO_OPT and the SDK's stable reduction layout; the tool cannot override it.

`BENCH_JAVA_HOME` can select a measurement JVM independently of the compiler.
The script passes `-Xms128m -Xmx128m -XX:+AlwaysPreTouch -XX:+UseSerialGC
-XX:ActiveProcessorCount=4`. This pins JVM configuration, not CPU affinity or a
container's CPU quota. Every measured JVM reports its actual version/arguments.

## Corpus and result consumption

The tool authenticates all 69 repository fixtures against the sizes and SHA-256s
in `src/test/resources/reference/path-manifest.json`, in manifest order. Those
public upstream files, their licenses and sources are unchanged. `corpus.csv`
records each size/digest and sequential result. No synthetic no-op or direct
model kernel replaces an SDK call. Each corpus pass produces 68 model results,
one empty-content rule result, and zero expected failures. Failure throughput is
not part of this corpus; failures are exercised in the resource workload and
existing tests instead.

Setup retains one immutable sequential oracle per corpus file. Each returned
result is compared for label, MIME, model version/use, raw prediction, score and
overwrite reason. Singles compare scores exactly; batches retain the existing
`1e-5` tolerance. Counters and a wrapping long checksum consume results. Call
results are not retained as a history. These comparisons check equivalent
behavior, not classification accuracy; official-reference accuracy comparisons
remain in the SDK tests.

Path reads warm filesystem-backed regular files. Stream opens the same file for
each request, consumes it to EOF through `identify(InputStream)`, verifies byte
count/ownership, and closes it as caller. This is a local file-backed upload
source, not a network upload measurement. No input bytes are preloaded by the
timed harness. Authentication and oracle creation happen before warmup and warm
the page cache. There is no cold-storage or startup-latency claim.

## Timing definitions

All durations use `System.nanoTime()` and are recorded in nanoseconds. Initial
asset authentication, SDK/session construction, oracle creation, warmup, CSV/JSON
writes and resource sampling are excluded from measured round wall time.
The SDK is reused across warmup and measurement rounds, then closed.

| Scenario / metric | Boundaries |
| --- | --- |
| Path request latency | Immediately before `identify(Path)` to return; result validation follows. |
| Stream request latency | Before opening the source through SDK identification, EOF/ownership checks and caller close; result validation follows. |
| Shared request latency | The same Path operation on a single shared SDK, from one of four application workers. No executor queue delay is included. |
| Batch call latency | One entire synchronous `identifyAll` over 690 lazy input positions, including all callbacks and their validation. Each configured batch holds at most `batchSize` inputs. |
| Batch item delivery latency | Timestamp just before iterator `next()` returns that input, to entry of its callback. Includes waiting for the current batch to fill/run and earlier callbacks. It is not elapsed time since the whole call started. |
| Batch amortized time per item | Sum of complete batch-call durations divided by total delivered items. It is not an individual item's latency. |

Throughput is total successfully consumed items / summed measured round wall
seconds; the wall interval includes result validation, loop/control overhead and,
for shared calls, start-gate release, completion joins and counter merging. It
excludes between-round output/sampling. Shared workers use a ready/start gate;
each runs a fixed strided share of the round in a closed loop, issuing its next
request only after the previous one finishes. This describes sustained callers,
not arrivals with externally imposed queueing. The SDK creates no worker pool.

`rounds.csv` includes warmup and measured workloads and counts. `latencies.csv`
holds every measured per-item duration, indexed by round and corpus position.
`summary.json` uses a weighted aggregate throughput, arithmetic mean and exact
nearest-rank p50/p95/p99 (`ceil(p*N)-1` after sorting), plus min/max/sample count.
It pools all item timings, including the empty rule case. Batch-call percentiles
use only the number of measured rounds. The arrays store timing scalars only,
with a one-million measured-sample cap; do not use this finite timing harness to
prove constant-memory processing of an unlimited sequence.

## Resources and coverage

`resources.csv` samples outside timed regions: before initialization, after
oracle creation, after every warmup/measurement round and after SDK close. It
records MemoryMXBean heap used/committed/max and nonheap used, Linux `VmRSS`,
`RssAnon`, `RssFile` (KiB), open descriptors and GC count/time. The observation's
own output and directory handles contribute to process-wide descriptor counts.
Sampling allocates small transient buffers. Performance runs do not request GC.
These are point observations, not sampled peaks or allocation rates.
The shared scenario's `after_sdk_close` sample still includes its idle application
workers; executor shutdown follows that sample.

The separate `resources` process defaults to 30 warmup cycles, then eight stages
of 25 complete cycles. Override `RESOURCE_WARMUP`, `RESOURCE_PHASES` and
`RESOURCE_CYCLES` if needed. Every cycle creates an SDK with two intra-op threads,
runs successful Path/stream/mixed batch inference, a missing Path, an IOException
stream, a callback abort after five deliveries, an iterator abort after 32,
and an interrupt after one. It verifies recovery with another inference, exits
the body exceptionally through try-with-resources, closes again, checks a retained
result and rejects a new call. Every cycle checks 73 observed model results,
16 rule results, 18 input failures, three batch aborts and one exceptional body
exit. Some model rows computed before batch abort are never delivered; the
73 counts delivered/returned model results, not all executed native rows.

Each resource stage samples before and after an explicit `System.gc()` request,
recording actual GC counters. It retains only the fixed corpus/oracles, current
batch, counters and one cycle's result; it stores no cycle result history. Heap
residency is controlled with the fixed pre-touched heap. RSS also includes JVM
native structures, JIT, stacks, shared libraries, ORT and allocator caches: neither
RSS nor RSS-minus-Java-heap measures precise ORT allocation. Interpret multiple
post-warmup stages and existing handle/cleanup regressions together. There is no
requirement for exactly constant RSS, and no performance SLA/pass threshold.

The original `PackagedProbe`, `BatchProbe`, `StreamProbe` and `LifecycleOrtProbe`
remain the SDK regression/resource gates. Full `mvn clean install` runs them in
independent JVMs, including the 2 GiB + 17 stream and 1,000,003 lazy Paths under
`-Xmx64m`. Those generators/consumers do not collect all inputs or results. Native
initialization, inference and release fault coverage remains in LifecycleOrtProbe;
the measurement tool does not add instrumentation to SDK internals.

`environment.txt`, `commands.txt`, `sources.sha256` and `artifacts.sha256` connect
the raw observations to the host, exact JVM commands, production/measurement
sources and consumed JARs. Run metadata also hashes the SDK JAR actually loaded.
The shell rejects a consumer JAR that differs from the current packaged SDK.
