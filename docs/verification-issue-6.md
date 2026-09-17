# Issue #6 verification record

Scope: [ordered bounded path batches #6](https://github.com/zerocloud-sdk/magika/issues/6),
under [parent specification #1](https://github.com/zerocloud-sdk/magika/issues/1).
Implementation and review base: `dab928e2e7d2529b7a9e6602e15e2f59286a3ba0` on `main`.
Both specifications and their comments were reread: #6/#1 open, no comments;
prerequisite #4 closed. The #7 lifecycle boundary was reread. The tracker is unchanged.

SDK `0.1.0`, upstream commit `9f225aa480e675af44343b9160f077073ed1b752`, bundled
`standard_v3_3` asset bytes/digests, ORT `1.30.0` and production dependency versions
remain unchanged. ASM `9.10.1` is added only to the test scope for observing the
public ORT boundary in an independent JVM. No public testing API is introduced.

## Reproduce

Run as an unprivileged user on Ubuntu 24.04 x64/CPU, so permission tests observe
an actual denied file open. Build with JDK 21 targeting Java 8:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -B -ntp -Dtest=BatchTest,PathReferenceTest test
mvn -B -ntp -Dtest=BatchTest#twoModelRowsPreserveSingleRowNumericalAccuracy \
  '-Dit.test=PackagedIT#realBatchShapesNativeFailuresAndResourceRelease+millionLazyPathsFitInLimitedHeapWithoutRetainingResults+batchSuccessAndAbortPathsReleaseResources' verify

audit_dir=$(mktemp -d /tmp/magika-batch-verification.XXXXXXXX)
for runtime in 8 17 21; do
  mvn -B -ntp -Dtest.java.home="/usr/lib/jvm/java-$runtime-openjdk-amd64" clean install \
    > "$audit_dir/java-$runtime.log" 2>&1 || exit 1
  mkdir -p "$audit_dir/java-$runtime"
  cp -R target/surefire-reports target/failsafe-reports "$audit_dir/java-$runtime/"
done
mvn -B -ntp -f examples/offline/pom.xml clean package
for runtime in 8 17 21; do
  bash scripts/verify-offline.sh "/usr/lib/jvm/java-$runtime-openjdk-amd64"
done
```

The matrix preserves reports before the next clean. Packaged probes use the
selected runtime's `java`, real SDK JAR and native ORT. Batch probes have a
300-second deadline and forced termination/termination checks, like the existing
native resource probes. The actual runtime home is asserted by the unit suite.
The independent consumer has no parent POM or SDK-source dependency and executes
`BatchExample` along with all existing examples in each Java-only, network-isolated
chroot. Maven, SDK runtime, CI, examples and all checked-in tests require no Python.

## Numerical compatibility diagnosis

The initial genuine multirow implementation failed the unchanged `1e-5` tolerance:
for two identical eight-byte inputs `80 81 82 83 84 85 86 87` (hex), a single row
scored `0.9995940327644348`, while two rows scored `0.9993979930877686`, a difference
of `1.9603967666625977e-4`. An official path case also differed by more than `1e-5`.
`BatchTest.twoModelRowsPreserveSingleRowNumericalAccuracy` preserves this public-API
regression; the broader boundary and reference comparisons caught the initial defect.

Independent real-ORT probes reproduced it without the SDK, under every graph
optimization level, ruling out Java feature packing and graph fusion as the source.
Intermediate-output inspection located the first divergence at LayerNorm_0's two
ReduceSum nodes; preceding Dense_0 and activation outputs matched. For example,
one sum changed from `432.02762` to `432.02463`; the variance calculation amplified
small accumulation differences. ORT's [CPU reduction implementation](https://github.com/microsoft/onnxruntime/blob/v1.30.0/onnxruntime/core/providers/cpu/reduction/reduction_ops.cc)
selects different reduction paths according to dimensions, including the batch axis.

`BatchModel` performs a narrow equivalent layout rewrite **after original asset
SHA-256 authentication**. For each of the two LayerNorm_0 sums, it transposes
`[batch,512,256]` to `[512,batch,256]`, then reduces axis 0 instead of axis 1;
the output remains `[batch,256]`. The original weights, other graph fields,
model I/O signature and all prediction rules remain unchanged. The edit is tied
to the authenticated fixed graph and requires exactly the two expected nodes.
No alternate model is downloaded or bundled. `getModelInfo()` digests continue to
identify the original upstream assets; the executed graph has this documented
layout adaptation. Graph optimizations are disabled because they can undo it.
This choice favors compatibility; throughput optimization/reporting remains #8.

The minimal real-ORT reproducer then had zero difference for 2/3/7/32 rows, and the
public boundary/reference suite passed without loosening existing single-entry
exact comparisons. All inference still runs as one actual multirow tensor per
nonempty model batch; there is no per-item inference retry or Java inference loop.
Diagnostic scripts stayed outside the repository. A small Python 3.12 standard-
library protobuf inspection script was used only for this independent diagnosis;
no Python package, script or dependency was added to the product/build/tests.

## Public behavior and native evidence

`BatchTest` covers null arguments and closed instances before iterator consumption;
empty sequences; defaults from create/builder; batch sizes 1 and 3 at exact/partial
boundaries; and default 32 with 0/1/31/32/33/64/65 inputs. Later builder mutations
cannot change built instances. Sizes 0, negative, 262144 and Integer.MAX_VALUE are
rejected; the maximum valid 262143 accepts a tiny actual sequence without eagerly
allocating maximum storage. Checked multiplication protects tensor element/byte
capacities. Long input and delivery counts cannot wrap: input-count exhaustion
aborts before another element is consumed.

Three prediction modes run mixed actual boundary files with model inputs, rule
results, missing paths, directories and duplicates at sizes 1/7/32. Every result
field, original Path identity and ordered index is compared to the single Path
entry; only batch scores use the parent tolerance. Per-file tests exercise actual
permission denial, missing/broken links, directories/devices, valid links, injected
IOException, closed-channel reads and real truncation, checking continuation,
original causes, one open/close and no retry. Existing special-file/FIFO regressions
continue in their killable packaged probes.

Iterator hasNext/next/null faults are tested at several positions before and after
whole-batch delivery. hasNext failures have no invented index; next/null failures
have their known position. Callback exceptions and Errors are tested at initial,
mid-batch and final positions, including a failed-file outcome. Only normal returns
count; recorded callback side effects remain, with no later calls or retry. A
latch-controlled slow callback holds the caller's thread and prevents consumption
of the next batch without using sleeps. Interrupt tests cover entry, hasNext, next,
actual file reads, mid-batch callbacks and the final callback of a partial batch;
all retain the flag, report the delivered prefix and release opened handles.

`BatchOrtProbe` uses a test-only Java instrumentation agent at the **public ORT
run(Map) boundary**, with no access to SDK fields or changes to SDK bytecode. Normal
runs use actual native inference. For nine mixed input positions with batch size 4,
it observes tensors `[2,2048]`, no native call for a rule/error-only batch, and
`[1,2048]` for the final batch. In every callback the recorded SDK input tensors
and ORT Results are already closed, using their public lifecycle methods.

The probe supplies a test-owned wrong-shape tensor on the second run to cause a
real native `ORT_INVALID_ARGUMENT`. The SDK's original input tensor is still
released. Exactly four outcomes remain delivered; no second-batch item is called.
The cause chain retains MagikaException(INFERENCE) and OrtException. Multiple
model rows report no input index; a single model row reports its actual input
position 6, excluding preceding rules/errors. The probe also checks callback
failure cleanup and subsequent SDK reuse. The fixture closes its own tensor;
it never closes an in-use SDK instance to manufacture a fault.
ASM is excluded from ordinary packaged-probe classpaths. Otherwise Java 8's
one-time JAR loading changes the existing native-loader descriptor baseline;
the original descriptor threshold remains unchanged, and the loader test passes.

## Full matrix and bounded-memory results

All full builds passed on Ubuntu 24.04 x64/CPU as unprivileged user `ubuntu`.
JDK 21.0.11 compiled Java 8 bytecode. XML reports confirm the following counts,
with **zero failures, errors or skipped tests** in every runtime:

| Actual test JVM | Unit tests | Packaged tests | Strict Javadoc | Isolated consumer |
| --- | ---: | ---: | --- | --- |
| OpenJDK 1.8.0_492 | 34 | 16 | Passed | Passed |
| OpenJDK 17.0.19 | 34 | 16 | Passed | Passed |
| OpenJDK 21.0.11 | 34 | 16 | Passed | Passed |

Every runtime retained 9,261 exact feature references, 141 content references,
207 original path references and 207 direct batch path comparisons. Maximum
absolute score error was `7.748603820800781e-7` for content and
`5.364418029785156e-7` for paths/batches; existing single-entry comparisons remained
exact. Actual permission denial, stream/file boundaries, native failures,
classfile/dependency/assets checks and resource regressions all passed.

The final 8/17 builds used isolated copies of staged tree
`51b2ce35a6b026d967c4826366f9ad650d8589af`; Java 21 ran in the main worktree.
After the descriptor diagnostic addition described below, the complete Java 21
build ran again. The only executable-source difference from the 8/17 runs is
failure-reporting code in BatchProbe, with the same assertion predicate. Production,
example and build sources are identical. The final committed tree is also checked
by all three CI jobs; their receipts accompany the delivery response.
Local logs and XML are preserved under `/tmp/magika-issue-6-evidence/` as
`java-{8,17,21}-final.log` and `java-{8,17,21}-final/`.

Each limited-heap JVM completed **1,000,003 fresh lazy Paths**, batch size **32**:
1,000,003 delivered, 999,758 successes, 245 per-file failures, 977 real model rows,
and only one retained result. The rule filename is 180 characters long, so all
fresh inputs could not fit in the heap. The generator retains three path strings
and a counter; the consumer retains one result and fixed counters.

| JVM | Actual maximum heap, bytes (`-Xmx64m`) | RSS samples, KiB | File descriptors |
| --- | ---: | --- | --- |
| 8 | 64,487,424 | 109328, 116348, 118152, 118412, 118644 | 36 → 36 |
| 17 | 67,108,864 | 113940, 156956, 151796, 156180, 156196 | 19 → 19 |
| 21 | 67,108,864 | 115912, 169772, 171788, 172172, 172188 | 18 → 18 |

The standalone consumer build passed. All three Java-only, network-isolated
chroots confirmed isolation and ran existing examples plus BatchExample:
`delivered=4, successful=3, fileFailures=1`; a callback abort reported
`delivered=2, inputIndex=2, callbackSideEffects=3`. The consumed SDK production
class bytes match the main-worktree packaged SDK. Consumer receipts are
`consumer-build.log` and `offline-{8,17,21}.log` in the same evidence directory.

Resource probes separately run 90 post-warmup cycles of successful mixed batches,
callback aborts, iterator aborts after a delivered prefix and interruptions.
They measure handles and resident memory and require no continuing handle growth
and a bounded allocator/JVM RSS tolerance, rather than exact RSS equality.

| JVM | Existing single-input RSS samples, KiB | Batch RSS samples, KiB | Single / batch file descriptors |
| --- | --- | --- | --- |
| 8 | 204972, 222832, 226536, 230616 | 444496, 452692, 466116, 469584 | 36 → 36 / 36 → 36 |
| 17 | 239552, 239688, 252480, 253108 | 464500, 460236, 496248, 496440 | 22 → 19 / 22 → 19 |
| 21 | 236336, 250472, 250532, 252864 | 463388, 482568, 497608, 499688 | 21 → 18 / 21 → 18 |

Both RSS probes use `-Xms128m -Xmx128m -XX:+AlwaysPreTouch` so Java heap residency
is fixed before the baseline sample. The original 96 MiB RSS tolerance, cycle
counts and descriptor assertions are unchanged. An initial Java 21 run with a
variable heap failed with RSS KiB `[117624,234604,235900,223352]`. A separate
instrumented reproduction also failed (`[124136,237560,246512,226688]`), while JVM
native-memory tracking showed heap commitment changing from 24 MiB to as much as
126 MiB. The same diagnostic with a resident fixed heap passed with RSS KiB
`[240800,251272,248324,260152]`; heap commitment stayed at 128 MiB and live heap
stayed near 7 MiB. This controls a JVM contribution to process RSS without
relaxing the native-resource regression. Diagnostic instrumentation remains
outside the repository; the full matrix reruns the original public probe.

One parallel Java 21 matrix run completed every million-path delivery/count
assertion but failed the final process-wide descriptor bound (`before + 2`).
That original assertion did not record the descriptor counts or targets, so its
cause is **unresolved**. Six isolated million-path diagnostic runs passed; the
three using the exact packaged classpath each measured 18 → 18 descriptors and
no retained input-file handles. Observed descriptors included JVM/JAR resources
and ORT's global cache database, but that does not establish the earlier cause.
The bound remains unchanged. Failure-only diagnostics now capture the final
count once and list descriptor targets; disappearing background descriptors
cannot turn a failed assertion into success. The full Java 21 rerun passed after
this diagnostic addition, retaining the original bound; final CI is a separate
delivery gate. The added diagnostics are not presented as a leak fix.

## Acceptance mapping

These 36 rows preserve every completion criterion in the execution handoff.
Delivery gates are completed after implementation acceptance and review; final
SHA, CI URL and workspace receipts accompany this record in the delivery response.

| # | Requirement | Authoritative evidence |
| --- | --- | --- |
| 1 | Public synchronous Iterator/Consumer API returning summary | Magika.identifyAll; BatchTest; standalone BatchExample. |
| 2 | Null Iterator/Consumer → IllegalArgumentException | Arguments test, zero iterator observations. |
| 3 | Closed instance → IllegalStateException | Closed-state test, zero iterator observations. |
| 4 | Default batch size 32 | create and default-builder consumption matrices. |
| 5 | Positive configuration; immutable built settings | 1/3/maximum tests, builder reuse and invalid settings. |
| 6 | No element/capacity arithmetic overflow | 262143 limit, overflow rejection, multiplyExact for elements and bytes. |
| 7 | At most batchSize inputs consumed per batch | Iterator counters in every callback at exact/partial boundaries. |
| 8 | No full history; slow consumer bounds lookahead | Latch test, million fresh paths under 64 MiB, one retained result. |
| 9 | No SDK worker pool | Calling-thread assertions; source review; existing native worker tests. |
| 10 | Only model rows in genuine ORT batches | Agent-observed [2,2048]/[1,2048], no call for rules/errors only. |
| 11 | Long zero-based index and original Path | Ordered/identity assertions including duplicates and failures. |
| 12 | Exactly result XOR error; matching success flag | Private value construction and every mixed-result assertion. |
| 13 | Mixed input types retain original order | Boundary matrix; native probe; duplicate positions. |
| 14 | All modes match singles, every field, score ≤1e-5 | 207 direct batch references; all mixed boundaries; precision regression. |
| 15 | Per-file failures continue | Real permission/missing/directory/device/link/read/truncation tests. |
| 16 | Faults never become unknown | INPUT outcomes vs native/system abort assertions; empty/unknown remain successes. |
| 17 | System, iterator, null and callback faults abort | Public fault matrix plus real native ORT failure probe. |
| 18 | Stage, delivered count, known index, original cause | ITERATION/IDENTIFICATION/CALLBACK/INTERRUPTED assertions; native cause chain. |
| 19 | Count only normally returned callbacks | Throwing callback's recorded side effect is excluded from progress. |
| 20 | Summary separates total/success/failure | Mixed summary assertions and million-path independent counters. |
| 21 | Valid delivered prefix; no later/duplicate callbacks | Iterator/callback/native fault tests and recorded ordered prefixes. |
| 22 | No retry or side-effect rollback | Fault side-effect lists and one-open/read/close observations. |
| 23 | Boundary interruption, flag and exact progress | Entry, iterator, read and callback interruption matrix. |
| 24 | Owned handles/tensors/results released; Java-only values | ObservedFile; public ORT lifecycle assertions; RSS/fd probes; retained outcomes. |
| 25 | Real ORT/files and controllable public behavior tests | BatchTest, BatchOrtProbe, BatchProbe, authenticated path references. |
| 26 | Large lazy input in separate limited-heap JVM | 1,000,003 fresh Paths; 32 batch; 64 MiB; only one retained result. |
| 27 | Existing feature/content/path/mode/stream/resource/package regressions | Full Maven suites retain all existing tests and exact single-entry assertions. |
| 28 | JDK21 compile to Java8; actual 8/17/21 CPU execution | Runtime-home assertions, classfile checks, matrix XML/logs and CI. |
| 29 | Strict Javadoc | doclint all + failOnWarnings in every full build. |
| 30 | Runnable batches, per-file failure, abort-progress example | BatchExample and README/Javadoc ownership/recovery/side-effects contract. |
| 31 | Independent consumer and no-network/no-Python regression | Standalone Maven build and three isolated consumer executions. |
| 32 | Fixed-base Standards and Spec review of all ticket changes | Independent reviews below, including staged/unstaged/new-file inventory. |
| 33 | Commit only #6 after acceptance/review | Explicit ticket-only staging and final commit receipt. |
| 34 | Authorized origin push; final Java8/17/21 CI green | Remote SHA and final Actions run/job receipts. |
| 35 | No ticket leftovers; original 24 untracked files unchanged | Initial/final path inventory and SHA-256 checks outside worktree. |
| 36 | Report evidence, review, SHA, CI and limits | This report plus final delivery receipt. |

## Review scope

The fixed base is `dab928e2e7d2529b7a9e6602e15e2f59286a3ba0`.
Two independent code-review agents reviewed all 23 ticket files using
`git diff --cached <base>`, plus `git diff` for unstaged changes and the complete
untracked inventory. HEAD equaled the baseline during review; its empty
`base...HEAD` range was not substituted for the actual implementation diff.

## Standards

No documented-standard violations or actionable baseline smells were found.
The review checked AGENTS.md, docs/agents, CONTEXT.md, all five accepted ADRs and
the complete Fowler smell baseline. The explicit #6 handoff defers shared
concurrency/close coordination to #7; the implementation preserves that boundary.

The highest-risk change is BatchModel with ModelAdapter. The rewrite follows
original asset authentication and changes only the two fixed reductions using
the existing axis-zero initializer. The reproduced numerical failure justifies
this narrow helper. Its executed-graph distinction and disabled optimization are
documented; the original assets, score tolerance and exact single-entry assertions
are preserved. Batch state, native inference ownership and public values have
separate responsibilities. Test instrumentation remains at the public ORT boundary
and introduces no public SDK testing surface.

This axis was a read-only source/evidence review, not an independent full test
run. All 24 protected-file hashes passed. Findings: **0 hard violations and
0 actionable smell judgements**. A follow-up reviewed the resident fixed-heap
RSS fixture and its diagnostic logs: no additional finding; workload, descriptor
checks and the 96 MiB threshold are unchanged.

## Spec

The review found no functional requirement gaps or unrequested scope. It checked
the synchronous/lazy/bounded ordering, model-only native batches, per-file
continuation, abort progress/cause, interruption, ownership and immutable-result
contracts against #6, parent #1/comments, the execution handoff and domain decisions.

One **P3 documentation finding** corrected the internal reduction shape in
BatchModel's comment and this report from `[batch,2048,256]` to `[batch,512,256]`.
The authenticated reshape constants establish 512 and 256; the executable
transpose/axis rewrite was already correct. The reviewer independently confirmed
both corrections. **Zero Spec findings remain open.**

An isolated Java 21 public-API probe compared 69 official path files plus
40 deterministic generated inputs in three modes, with batch sizes 2/7/32/65
and intraOpThreads 1/2/4: **3,924 comparisons passed**, maximum absolute score
difference **2.384185791015625e-7**. Labels, MIME, raw labels, rewrite reasons and
model-use flags matched. The probe used a separate temporary classes directory;
it did not alter Maven target or any protected file.

The reviewer also independently confirmed the final RSS fixture as a justified
control for measured JVM heap expansion, with unchanged assertions and production
behavior. No additional Spec finding was raised.
The descriptor follow-up found no demonstrated production defect and required
keeping the original bound and disclosing the unexplained transient. Standards
also reviewed the failure-only diagnostic helper without additional findings.

Review totals: Standards **0**; Spec **1 P3 documentation finding, corrected**;
no outstanding findings on either axis. Full matrix and delivery receipts are
separate acceptance evidence, not inferred from source review.

## Limits and workspace preservation

Only #6 is implemented. Shared concurrent use and close coordination remain #7;
callers serialize the whole batch with other instance operations. No lock is
held during callbacks. Interrupt observation cannot forcibly stop blocking input,
user callbacks or native inference. Caller-owned iterators/resources, scheduling
and recovery stay with the application; callback side effects cannot be rolled back.
The 64 MiB guarantee concerns Java heap in the recorded probe, not total process
RSS: model and ORT native allocations are separate and grow with batch size.
Graph optimization is disabled for score compatibility; no throughput or latency
claim or #8 performance report is made. Compatibility is limited to tested inputs
and Ubuntu 24.04 x64/CPU; the references do not prove all-class accuracy.
The unexplained, non-reproduced descriptor assertion above remains a test-stability
limitation; passing reruns do not establish its cause.

The 24 original untracked files under `.scratch/`, AGENTS.md, CONTEXT.md, docs/adr/,
docs/agents/, docs/design-session.md and docs/upstream-findings.md are protected
by a SHA-256/path inventory outside the worktree and are never staged. No ignore
rule changes. No PR, merge, tracker edit, issue closure, tag/Release, Central
publication, other-repository write or external message is part of this work.
