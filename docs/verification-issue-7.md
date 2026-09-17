# Issue #7 verification: shared instance and draining close

Scope: [#7](https://github.com/zerocloud-sdk/magika/issues/7), under
[parent #1](https://github.com/zerocloud-sdk/magika/issues/1), both read with
comments before implementation (no comments). Fixed implementation/review base:
`48d198fa11d7fd42429f5ebc69ca5797c639837e` on `main`.

## Implementation and observable behavior

`Magika` admits each complete public identification call under a short lifecycle
monitor and unregisters it in `finally`. OPEN → CLOSING stops admission before
waiting; only the closer that starts this transition releases the adapter.
CLOSED is published and all waiting closers notified even when release fails.
Input sampling, inference, iteration, callbacks and native release run outside
the monitor. A per-instance thread-local nesting count rejects same-thread close
before any transition or wait, including after a nested identification returns.

An accepted batch keeps its admission for the entire synchronous invocation,
including later input batches and its final callback. Existing failures and
interrupts still end the batch with its original stage and delivery prefix.
Close waiting is uninterruptible, with the flag restored on exit. Concurrent and
later closes observe the same completed release; operational failures are
reported in separate RESOURCE_RELEASE exceptions retaining the cause chain,
without a native-release retry. The releasing thread retains the adapter's serious Error behavior; later closers
receive a fresh RESOURCE_RELEASE exception carrying that Error.

The adapter's existing per-invocation tensor/result ownership and Session-before-
SessionOptions cleanup remain unchanged. No production dependency, SDK worker
pool, public testing API, asset byte, version, numerical adaptation, score
comparison or resource threshold changed.

## Behavior and native-boundary evidence

`ConcurrencyTest` adds five public-API tests. Four workers run byte[], Path,
InputStream and identifyAll against the same instance in every prediction mode.
The 63 existing boundary contents are each compared twice through all four
entries: **1,512 full-result comparisons**. Labels, MIME, scores, raw prediction
presence/label/score, overwrite reason, model-used flag and model version are
checked. Existing single-entry comparisons remain exact; batches retain 1e-5.

Controlled stream/file reads establish two accepted calls before close. The
stream exits first while the file remains blocked; no closer may finish yet.
New calls through all four entries fail without reads/opens/iterator consumption
or callback delivery. Owner and concurrent waiting closers are interrupted;
an already-interrupted waiter is also covered. All restore their flags on exit.

Another test holds the first batch callback, then a later iterator input, then
the last callback of a three-batch call. Close cannot finish at any boundary.
A callback also waits successfully for identification on another thread before
close starts, demonstrating that user code does not hold the lifecycle lock.
Read, hasNext, next and callback code attempt same-thread close; nested calls
return first to verify nesting is preserved. Rejection leaves the instance open;
closing a different instance from the callback remains allowed.

Read failures, iterator/callback failures and an interrupted batch execute while
close is waiting. They retain category/cause/progress, close owned file handles,
unregister the call and allow shutdown to finish. Earlier BatchTest/StreamTest/
PathTest behavior matrices continue to cover their full existing contracts.

`LifecycleOrtProbe` runs against the packaged SDK in a separate JVM using the
existing test-only instrumentation pattern, extended to public ORT run, signature,
configuration and close boundaries. It never accesses SDK fields or locks or
rewrites SDK classes. Collectors and hooks are safe for concurrent callers.
Four calls rendezvous at run(Map): all use the **same actual OrtSession** and
four distinct input tensors. Real native inference returns four distinct Results;
a second barrier holds them before SDK result copying. Close waits at both
barriers and starts releasing only after every call exits. All call resources
are then publicly observed closed and retained Java results/provenance remain usable.

A test-owned wrong-shape tensor produces a real ORT_INVALID_ARGUMENT while close
waits; the original SDK tensor is still closed and shutdown completes. Resource
probes hold Session and SessionOptions release separately, proving other closers
wait through the entire release, not merely through active-call drainage.
Success, Session failure, options failure, both failures, LinkageError and serious
Error cases each attempt Session then options exactly once. Waiters wake,
interruption survives, later calls reject, repeated close does not release again,
and another live instance continues inference. No OrtEnvironment.close is invoked.
Release faults are injected **after real native release** so the fixture does not
intentionally leak handles; this verifies reporting/cleanup control flow, not
that a native library can recover a handle when its actual release fails.

Initialization faults before Session creation and after a real Session exists
cover RuntimeException and Error. Already-owned resources get ordered cleanup,
cleanup failures remain suppressed, publicly closed resources reject reuse, and
a subsequent healthy instance still performs native inference. Existing packaged
asset/native-loader failures and process RSS/descriptor trends remain in the
full suite.

An additional independent probe found that rethrowing the same serious Error
from explicit and automatic try-with-resources close triggers Java's
self-suppression IllegalArgumentException. The initiating Error is now preserved
and later closes wrap the saved cause in a fresh exception. Both operational and
Error regressions verify the automatic second close, suppressed failure and one
release. The initial Java 8 matrix passed before this fix; that run is archived
as preliminary evidence and the full final matrix is rerun on the corrected tree.

The first new shutdown test failed against the fixed baseline: a previously
accepted call attempted inference after the original close released its Session.
After the lifecycle change, the focused 29-test suite and both native boundary
probes passed. Coordination uses latches, public rejection and bounded observation
of a closer's Java WAITING state; no fixed sleep or private lifecycle layout is
used. All waits have test deadlines and packaged probes have process timeouts.

## Reproducible acceptance

Environment: Ubuntu **24.04.4 LTS**, Linux x86_64/CPU, unprivileged `ubuntu`
(uid 1000), four virtual CPUs (Intel Xeon Skylake). Compiler: OpenJDK 21.0.11.
ORT remains 1.30.0, SDK 0.1.0, model standard_v3_3 and upstream
9f225aa480e675af44343b9160f077073ed1b752. Asset SHA-256 values and the #6 stable
reduction layout are unchanged.

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
for runtime in 8 17 21; do
  mvn -B -ntp -Dtest.java.home="/usr/lib/jvm/java-$runtime-openjdk-amd64" clean install
  # Save target/surefire-reports and target/failsafe-reports outside target before clean.
done
mvn -B -ntp -f examples/offline/pom.xml clean package
for runtime in 8 17 21; do
  bash scripts/verify-offline.sh "/usr/lib/jvm/java-$runtime-openjdk-amd64"
done
```

All final builds used the same executable-source tree, compiled with JDK 21
and `--release 8`. Surefire/Failsafe XML confirms the actual test JVMs below;
all counts have **zero failures, errors or skips**. The independent consumer
build also passed. Each network-isolated Java-only chroot ran the existing
examples plus SharedInstanceExample (16 requests, four application workers,
retained PDF result after close).

| Test JVM | Actual runtime version | Unit tests | Packaged integration tests | Strict Javadoc | Offline consumer |
| --- | --- | ---: | ---: | --- | --- |
| 8 | 1.8.0_492-8u492-ga~us2-0ubuntu1~24.04.1-b09 | 39 | 17 | Passed | Passed |
| 17 | 17.0.19+10-1-24.04.2-Ubuntu | 39 | 17 | Passed | Passed |
| 21 | 21.0.11+10-1-24.04.2-Ubuntu | 39 | 17 | Passed | Passed |

Every JVM retained the 9,261 exact feature references, 141 content references,
207 path references and 207 direct batch reference comparisons. Maximum absolute
score errors remained `7.748603820800781e-7` for content and
`5.364418029785156e-7` for path/batch results. Public concurrent checks add 1,512
comparisons per JVM (4,536 total); the packaged native lifecycle probe runs on
each JVM too. Real permission denial was exercised as uid 1000. Packaging checks
validate Java 8 classfiles/dependencies, authenticated assets and license metadata.

Existing single-call and batch resource probes retain their fixed resident heap,
96 MiB RSS tolerance, workloads and descriptor bounds. Each million-path probe
processes 1,000,003 fresh lazy Paths under a 64 MiB heap, delivers all inputs,
reports 245 per-file failures and 977 real model rows, and retains one result.
No bound was relaxed. The process observations are:

| JVM | Single-call RSS KiB / descriptors | Batch RSS KiB / descriptors | Million-path RSS KiB / descriptors |
| --- | --- | --- | --- |
| 8 | [207492, 224356, 226432, 227064] / 39 -> 36 | [444756, 449848, 470284, 469748] / 36->36 | [102604, 115632, 117760, 117832, 117840] / 36->36 |
| 17 | [236996, 249268, 248300, 253248] / 21 -> 19 | [463612, 484116, 494260, 494328] / 22->19 | [114112, 166140, 168136, 168520, 168524] / 19->19 |
| 21 | [236684, 250860, 250964, 255200] / 20 -> 18 | [463072, 482676, 497720, 495672] / 21->18 | [115928, 170776, 173024, 173200, 173216] / 18->18 |

Receipts are saved outside the worktree in `/tmp/magika-issue-7-evidence/`:
`java-{8,17,21}.log`, `java-{8,17,21}/{surefire,failsafe}-reports/`,
`consumer-build.log`, and `offline-{8,17,21}.log`. The 124-file source/resource
fingerprint in `tested-sources.json` was checked against the final worktree.
After the main review only receipt text and a stale example comment changed;
the independent consumer build includes that comment correction. The preliminary
Java 8 run before the Error-replay fix is archived separately and is not used for
final acceptance. Packaged, installed and consumer SDK JARs have identical SHA-256:
`0b6100573efff99e49b2c2288a820a72bcac25e9ce09d17c47cf4f029df27ca3`.


## Requirement-by-requirement acceptance

| # | Handoff requirement | Evidence |
| --- | --- | --- |
| 1 | Unified four-entry OPEN → CLOSING → CLOSED | Magika admission/finally; ConcurrencyTest four-entry rejection; native probe byte[] in flight. |
| 2 | Shared real Session matches sequential results, every field | 1,512 full-result comparisons in all modes; native identity and rendezvous; unchanged tolerance helpers. |
| 3 | Independent mutable sampling/features/native I/O | Mixed concurrent boundary matrix; distinct native tensor/Result identities. |
| 4 | Reject new calls without consuming input | Controlled closing/closed state, untouched stream/path/iterator/callback assertions. |
| 5 | Accepted singles finish safely | Two simultaneous gated stream/file calls, plus gated native byte[] call. |
| 6 | Drain entire batch, remaining inputs and all callbacks; retain abort/interruption | Three-batch gate sequence and close-with-fault matrix; existing BatchTest. |
| 7 | Release Session and options once after drain | Native event identities/counts; repeated/concurrent close and both inference barriers. |
| 8 | Session before options | Public ORT close entry/completion records, including failures. |
| 9 | Preserve shared environment/other instances | No environment close events; other live and later-created instances infer successfully. |
| 10 | Results/model information survive close | Full retained-result comparison and retained provenance; existing immutable-value tests. |
| 11 | Repeated/concurrent close waits for same completed close | Active input gates and separate Session/options release gates; no duplicate release. |
| 12 | Interrupted close still drains before releasing | Interrupted owner and waiter with active input; pre-interrupted closer at native release. |
| 13 | Restore closer interrupt flags | Owner, waiter and pre-interrupted waiter exit assertions on success/failure. |
| 14 | Report release failures and attempt other cleanup | Session/options/both/LinkageError/Error probes; causes and suppression assertions. |
| 15 | Failure leaves unavailable state and no stranded waiters | Bounded concurrent closer completion, rejected identification and repeated failed close. |
| 16 | Same-thread reentry rejects without changing state | Stream/file reads, hasNext/next and callbacks; nested-call depth; OPEN and CLOSING cases. |
| 17 | No lifecycle lock held during callbacks | Callback waits for another thread's identification; close reaches rejection while callback is held. |
| 18 | Coordinated tests, no sleeps/private locks | Latches, public boundaries/rejection and Java thread waiting observation; bounded test/process deadlines. |
| 19 | Initialization/read/inference/iterator/callback/release cleanup | Initialization and native-fault probes; ConcurrencyTest failure matrix; existing handle/RSS probes. |
| 20 | Batch order/progress/interruption/errors unchanged | Full BatchTest, BatchOrtProbe, PathReferenceTest and limited-heap BatchProbe. |
| 21 | Ownership/bounded sampling/three modes unchanged | Full StreamTest/PathTest/reference/boundary suites and packaged limited-heap probes. |
| 22 | Actual Java 8/17/21 complete regression on Ubuntu 24.04 CPU | Full matrix logs/XML, selected-JVM assertions, packaged native lifecycle probe. |
| 23 | Strict Javadoc, independent build and offline consumers | doclint all/failOnWarnings; standalone Maven project; three Java-only network-isolated chroots. |
| 24 | Runnable long-term sharing/shutdown and current docs | SharedInstanceExample (16 requests/4 application workers), README, class/package Javadoc. |
| 25 | Document unbounded blocking and caller-created wait cycles | README shutdown section and close/identifyAll/package Javadoc. |
| 26 | Fixed-base Standards/Spec review and findings handled | Independent review records below, covering actual staged/unstaged/new-file scope. |
| 27 | Local commit after all gates | Final commit SHA and gate receipts in delivery response. |
| 28 | No ticket leftovers; protected untracked files untouched | External 24-file path/SHA-256 inventory; final clean tracked/index status and exact untracked inventory. |
| 29 | Report each gate, actual JVMs, review, SHA, workspace and limits | This record and final delivery receipt. |

## Standards

Independent Standards review: **0 findings** (no documented-standard breaches or
actionable smell-baseline judgments). It inspected all 12 ticket files with
`git diff --cached 48d198fa11d7fd42429f5ebc69ca5797c639837e`, the empty unstaged
diff and complete untracked inventory. HEAD still equaled the fixed base because
the required review precedes commit; an empty base...HEAD range was not used as
a substitute for the actual staged implementation.

The review covered AGENTS.md, docs/agents, CONTEXT.md, all five accepted ADRs and
the full Fowler smell baseline. It confirmed ADR-0005 admission/drain semantics,
full-call registration, no callback/native work under the monitor, once-only
release and Error replay without self-suppression. All 24 protected hashes and
untracked status matched. This was a read-only review, without an independent
Maven run; matrix completion is proved by the recorded receipts. The reviewed staged
snapshot SHA-256 was
`fcdb9c95a0552717839e7d386e7155d3f243084af6051368af6ec11e135f10b8`.
Subsequent receipt text and a stale example comment correction do not change
reviewed executable code; the example correction is also reviewed separately.

## Spec

Independent Spec review: **0 findings**. No missing/partial implementation,
unrequested scope or incorrect contract behavior was found across all 12 ticket
files against the fixed base, #7/#1 snapshots and comments, the user's 29 completion
criteria and accepted ADRs. The review checked full-call registration/finally,
nested reentry, rejection, batch drainage, lock boundaries, interrupted/concurrent
closers, release failure convergence and the self-suppression regression.

It confirmed the public/native tests substantiate shared Session identity,
distinct tensors/results, every result field, resource ordering and cleanup,
environment isolation and retained values, with unchanged exact comparisons and
1e-5 tolerance. All 24 protected files matched. This was read-only and did not
claim independent test execution. Final matrix, Javadoc, consumer and offline
receipts are separate commit gates; no additional material evidence gap was found.

A final search found one stale sequential-use comment in SavedUploadExample.
It now recommends one long-lived instance across concurrent requests. Both
reviewers independently checked that additional comment-only hunk and found no
issue; the complete reviewed ticket scope is **13 files**. The independent
consumer is built after this correction. No executable statement changed after
the main reviews.

Review totals: **Standards 0; Spec 0; no outstanding findings**.

## Limits and protected work

Close can wait indefinitely for a blocking source, callback or native invocation;
it is not cancellation. Queued application tasks are accepted only when they
enter an SDK method. Same-instance callback/closer wait cycles are the caller's
responsibility. No new platform, performance SLA, all-class accuracy, release or
publication claim is made. #8 performance work and subsequent publication remain
outside #7.

The unexplained descriptor assertion recorded in `verification-issue-6.md`
remains a historical test-stability limitation. Passing this matrix cannot prove
its original cause or constitute a fix. All original resource bounds are kept.

The original 24 untracked files under .scratch/, AGENTS.md, CONTEXT.md, docs/adr/,
docs/agents/, docs/design-session.md and docs/upstream-findings.md are protected by
an external SHA-256/path inventory and excluded from staging. This ticket requires
a local commit only: no push, PR, merge, tracker edit/closure, tag/Release, Central
publication, other-repository write or external message is performed.
