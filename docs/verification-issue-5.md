# Issue #5 verification record

Scope: [bounded stream identification #5](https://github.com/zerocloud-sdk/magika/issues/5),
under [parent specification #1](https://github.com/zerocloud-sdk/magika/issues/1).
Both issues and their comments were reread before implementation: no comments,
#5 open and ready-for-agent, native blocker #3 closed. The tracker is unchanged.
Implementation and review base: `dc14db9897ff143a4c86cc77bbc17eddaf199de6` on `main`.

SDK `0.1.0`, model `standard_v3_3`, ORT `1.30.0`, dependency versions, fixed upstream
`9f225aa480e675af44343b9160f077073ed1b752`, model assets and reference bytes remain
unchanged. This work implements only #5. Batch identification, concurrent lifecycle,
performance reporting and release publication remain separate tickets.

## Reproduce

Compile using JDK 21 and Java 8 release targeting. Run Maven as an unprivileged user
so the existing permission regression observes a real denied open. Separate stream
and Path probe JVMs have 45-second deadlines, forced termination and a termination
check; the existing native probes retain their 300-second deadlines.

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -B -ntp -Dtest=StreamTest,PathTest,ReferenceTest,PathReferenceTest,MagikaTest,ModelBoundaryTest test
mvn -B -ntp -Dtest=StreamTest \
  -Dit.test=PackagedIT#generatedStreamLargerThanHeapAndIntRangeUsesBoundedSampling verify
# Preserve reports outside target before each clean.
audit_dir=$(mktemp -d /tmp/magika-stream-verification.XXXXXXXX)
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

The standalone consumer compiles against the installed artifact, has no parent POM
or dependency on SDK source, and executes `StreamUploadExample` in addition to the
existing byte-array, prediction-mode and saved-file examples. The isolation script
creates a Java-only chroot and network namespace, verifies the absence of an
external network interface and excludes Maven/Python. No Python step is used by
Maven, Java tests, examples or the SDK.

## Sampling and behavior evidence

`Magika.identify(InputStream)` uses the existing `InputSample` representation and
unchanged `Features`/`ModelAdapter` pipeline. `InputSample.fromStream` allocates
three 4096-byte arrays exactly once: first window, circular last window and work
buffer. At EOF it reuses the work buffer to linearize the tail. No allocation
depends on total length, and the loop allocates no collection, growing buffer or
temporary file. At most 12,288 bytes of byte-array sampling storage are live,
plus fixed object/counter overhead. Short inputs share the head as their tail.
This bound excludes the model, ORT, caller buffers and retained results.

Every bulk read is bounded by `maxStreamBytes - length`; the total advances only
by accepted bytes. When the total equals the limit, a single-byte read distinguishes
EOF from overflow, without incrementing the total or computing `limit + 1`.
Consequently both subtraction and accumulated length remain safe at `Long.MAX_VALUE`.
An excess byte produces an INPUT exception with the configured limit immediately,
before inference. IOException/SecurityException causes are preserved as INPUT errors.
There are no stream close, reset, skip or replay operations in the SDK.

`StreamTest` covers:

- The same 63 short/UTF-8/whitespace/window boundary inputs as Path, plus seven
  deterministic random inputs around 4096/8192 and at 32785 bytes. Five chunk
  patterns (1, 7, 4096, 8192 and varying 1/113/3/4095/17) run in all three modes:
  1,050 public full-result comparisons, each with an exact comparison through the
  existing restricted feature boundary. No new public testing API is introduced.
- A nonzero current position with an exact remaining-content limit; EOF consumption,
  reuse at EOF, retained results after SDK close, and subsequent caller-controlled
  reset/close. Non-resettable streams and streams reporting available() == 0 are
  also exercised. A zero-returning bulk read falls back to a single-byte read.
- Both `create()` and a default builder accepting exactly 67,108,864 bytes, and
  rejecting a virtually unbounded source after exactly 67,108,865 bytes without EOF.
  These default-limit sources retain only a fixed repeating pattern.
- Custom limits 0, 1, 7, 4095, 4096, 4097 and 8193, with empty, below-limit,
  exact-limit, one-byte excess and larger inputs. Overflow consumes exactly one
  excess byte, never drains the stream or returns a result, and retains ownership.
- Negative limits including `Long.MIN_VALUE`, valid `Long.MAX_VALUE`, immutable
  built configuration after builder reuse, and unchanged byte-array behavior.
- Read failures at positions 0, 7, 5000 and the 8193-byte limit, including the EOF
  probe. Each preserves the identical IOException, reports INPUT and consumed-length
  context, stops at the fault and leaves the SDK reusable. No close/reset happens,
  even when the SDK is subsequently closed. Null streams fail as arguments;
  closed instances reject before the first read.

`ReferenceTest` runs all 141 content references through both byte[] and stream
inputs. `PathReferenceTest` runs all 207 path references through Path, complete
byte[] and actual file streams opened/closed by the test. The original manifest
and all 69 original files are authenticated before use. Every stream result is
compared directly with the official reference and exactly with the other Java
entry points: raw/final labels, MIME, overwrite reason, score/raw score, model
version, model-use flag and optional raw prediction. Each mode has 47 content
cases (43 model uses) and 69 path cases (68 model uses). Rule results explicitly
retain absent raw predictions, score 1.0, no model use and reason NONE.

The inherited Path boundaries now use the shared fixture factory without changing
their content or dropping any checks. All 9,261 official feature cases, 189 Path
boundary comparisons, 1,284 deterministic threshold comparisons, zero-score cases,
native resource tests, input errors, permission denial and packaging checks remain.
The fixed fixtures do not establish accuracy for all 214 model classes.

## Large generated stream

`PackagedIT.generatedStreamLargerThanHeapAndIntRangeUsesBoundedSampling` starts a
separate `-Xmx64m` JVM on the selected test runtime. `PackagedProbe` verifies the SDK
was loaded from its built JAR, then delegates to `StreamProbe` using public APIs.
The input contains 2,147,483,665 bytes, exceeding both the actual maximum heap and
`Integer.MAX_VALUE`. The builder explicitly raises `maxStreamBytes` to that length.

`GeneratedStream` holds only a deterministic 4096-byte repeating pattern and long
counters. The consumer retains only an 8209-byte complete comparison input with
identical head/tail windows; it never collects the large stream. The probe checks
the total consumed, observed EOF, real model use, every result field and ownership
after SDK close. The unaligned final 17 bytes exercise circular-tail ordering.
The heap-limited run and source inspection together demonstrate fixed sampling
storage; this is not a claim about constant total process RSS or ORT memory.

## Execution evidence

Environment on 2026-09-17: Ubuntu 24.04.4 x64 / CPU, glibc 2.39, Maven 3.8.7;
compiler Ubuntu OpenJDK 21.0.11 using `--release 8`. Maven ran as `ubuntu`, UID 1000.
The actual test JVM home is asserted, and every runtime reports native ORT 1.30.0
with PDF inference score 0.9922314882278442.

| Actual test/runtime JVM | Full `clean install` | Isolated consumer, including stream example | Actual generated-stream max heap |
| --- | --- | --- | --- |
| OpenJDK 8u492, build 25.492-b09 | 24 unit/contract + 13 integration; 0 failures/errors/skips | Passed | 64,487,424 bytes |
| Ubuntu OpenJDK 17.0.19+10 | 24 unit/contract + 13 integration; 0 failures/errors/skips | Passed | 67,108,864 bytes |
| Ubuntu OpenJDK 21.0.11+10 | 24 unit/contract + 13 integration; 0 failures/errors/skips | Passed | 67,108,864 bytes |

Each runtime passes all 9,261 feature, 141 content and 207 path references,
1,050 stream boundary comparisons and the retained 189 Path boundaries.
Maximum absolute official score error is `9.5367431640625e-7` for content and
`7.152557373046875e-7` for path content; all fields/scores are exactly equal
between Java entry points. Both threshold policies retain their 642 comparisons.
Strict Javadoc, authenticated packaged assets, Java 8 SDK/dependency bytecode,
corrupt/missing assets, wrong model signatures and native load failures all pass.

Every generated-stream probe consumes 2,147,483,665 bytes to EOF. The result is
raw `randombytes`, final `unknown`, MIME `application/octet-stream`, score
`0.9968737363815308`, reason `OVERWRITE_MAP`, with model use. The retained
5,368,709,137-byte Path probe reads only 8,192 bytes, opens/closes one handle and
returns its expected real ORT result. Permission probes observe actual NIO and
SDK AccessDeniedException failures on all three JVMs.

The existing Path descriptor samples remain `[36, 36, 36, 36]` on Java 8,
`[19, 19, 19, 19]` on 17 and `[18, 18, 18, 18]` on 21, with zero remaining target
handles after 300 successes, 300 truncations and 300 read failures without GC.
Existing post-warmup native resource RSS KiB samples are
`[117228, 134160, 135072, 142064]`, `[118048, 130828, 132500, 137348]` and
`[109088, 125128, 126128, 128676]`, respectively, within the inherited 96 MiB
tolerance. Native file handles end below baseline and configured workers are
released on close. These are regression checks, not a performance report.

The independent consumer builds successfully and runs all examples on each of
the three JVMs without an external network interface, Python or Maven in its
runtime filesystem. The new example identifies the PDF after its envelope,
observes EOF and checks score `0.9922314882278442` and all expected result fields.
The existing isolation harness omits /proc and CA stores, producing native
environment warnings; all inference and isolation assertions pass.

All three clean builds and the consumer's installed SDK dependency have identical
SHA-256 `713962a67fc8c450187b9b004df27962a878623280ed1e0ddb0b4eb5a8ef535b`.
Execution logs, per-runtime Surefire/Failsafe XML, summary, issue snapshots and
ownership snapshots are retained outside the worktree at
`/tmp/magika-issue-5-evidence/`. `matrix-summary.json` records counts parsed from
XML alongside the actual JVM, reference, memory and offline-consumer output.

## Acceptance mapping

The numbered rows preserve all completion criteria in the execution handoff.
The AC column maps them back to issue #5's seven acceptance criteria. Commit/push
receipts follow the implementation and review gates; their final SHA, CI URL and
workspace checks are part of the final delivery receipt.

| # | AC | Requirement | Evidence |
| --- | --- | --- | --- |
| 1 | 1 | Public stream entry consumes current position to EOF | `identify(InputStream)`; nonzero-position and EOF tests; actual file streams in all path references. |
| 2 | 1 | Fixed head/tail/work storage and long total; no whole-input storage | Three fixed arrays in `fromStream`, reused work buffer; heap-limited generated source and consumer; source review. |
| 3 | 1, 5 | Every result field equals complete bytes in all three modes | `Fixtures.assertEquivalent` across references and 1,050 stream boundaries. |
| 4 | 2 | Default 67,108,864-byte limit | Both `create()` and default builder accept the exact length and fail after one extra byte. |
| 5 | 2 | Nonnegative long configuration, negative rejection, no arithmetic overflow | Negative/Long.MIN_VALUE assertions, Long.MAX_VALUE identification, subtraction/probe arithmetic review and >int-range generated input. |
| 6 | 2 | Built limit immune to later Builder mutation | Instances built at 1, 0 and Long.MAX_VALUE retain behavior after the builder changes to 2. |
| 7 | 2 | Zero accepts only an empty stream | Custom-limit and builder tests cover empty success and first-byte rejection. |
| 8 | 2 | Automated default/custom/exact/overflow coverage | StreamTest default/custom limit matrices. |
| 9 | 2, 3 | At most one excess byte; stop immediately | Observed consumed positions and EOF flags, including large/unbounded sources and exact-limit-plus-one input. |
| 10 | 3 | Explicit INPUT overflow with limit context; no truncated result | `limitFailure` requires an exception, category INPUT, `maxStreamBytes` context and an exceeds message. |
| 11 | 3 | Preserve original read cause; never disguise failures | Identical IOException at initial/midstream/probe positions; no result returned. |
| 12 | 4 | Never close caller stream on success/failure | Close counters on boundary, current-position, limit and read-failure cases, including SDK close. |
| 13 | 4 | Never reset caller stream on success/failure | Reset counters and non-resettable sources in the same tests. |
| 14 | 4 | Public current-position/chunking/short-read/non-resettable/error tests | Eight StreamTest methods use the public API; only exact features use the permitted restricted boundary. |
| 15 | 4 | Null stream is an illegal argument | Typed-null public overload assertion. |
| 16 | 4 | Closed SDK rejects before reading | IllegalStateException plus zero reads assertion. |
| 17 | 5 | Three-mode short/UTF-8/whitespace/window equivalence | 70 boundary contents × five chunk patterns × three modes, with exact feature and full-result assertions. |
| 18 | 5 | All 141 content and 207 path-content references | Direct stream reference assertions, exact entry-point equality, authenticated original bytes, 1e-5 official score tolerance. |
| 19 | 3, 5 | Empty/unknown are results; rule-only contract retained | Reference assertions and short/empty/binary/UTF-8 boundary equivalence; existing explicit rule tests preserved. |
| 20 | 6 | Independent limited-heap JVM handles generated input larger than heap | Packaged StreamProbe: 2,147,483,665 bytes, explicit raised limit, observed actual max heap, real ORT result, fixed generator/consumer storage. |
| 21 | 7 | Existing feature/content/Path/threshold/native/package regressions pass | Full clean Maven matrix retains every existing test and adds eight stream tests and one packaged probe. |
| 22 | 7 | JDK 21 compile to Java 8; actual Java 8/17/21 CPU execution | Runtime-home assertions, logged actual JVMs/ORT, Java 8 class checks and existing Ubuntu 24.04 CI matrix. |
| 23 | 7 | Strict Javadoc and independent offline consumer run stream example | doclint all/failOnWarnings; standalone consumer invokes StreamUploadExample in each isolated Java-only environment. |
| 24 | 7 | Full stream contract in example/README/Javadoc | Current position/EOF, limits, ownership, caller replay, source-controlled timeouts and sampling-memory exclusions documented. |
| 25 | Review | Fixed-base Standards and Spec review; no blocking findings | Two independent code-review reports below, including staged/unstaged/new-file inventory. |
| 26 | Delivery | Commit only #5 after acceptance/review | Explicit ticket-file staging, reviewed diff and final commit receipt. |
| 27 | Delivery | Authorized push; final SHA passes existing three-version CI | Final remote-SHA and GitHub Actions receipt. |
| 28 | Delivery | No ticket leftovers; preserve 24 untracked originals | Original path/hash snapshot checked before/after commit; empty tracked/index diff and exact untracked inventory. |
| 29 | Delivery | Complete execution report | This mapping, commands, matrix, references, memory evidence, reviews and final SHA/CI/workspace receipt. |

## Review scope

The fixed baseline is `dc14db9897ff143a4c86cc77bbc17eddaf199de6`.
The review uses the complete staged diff against that baseline, plus unstaged
changes and the full untracked inventory. Since HEAD equals the baseline before
commit, the empty `base...HEAD` diff is not substituted for the implementation.
Both reviewers inspected all 17 staged ticket files, the empty unstaged diff and
the exact protected untracked inventory. Their independent reports follow.

## Standards

No documented-standard violations or actionable smell-baseline findings were found.

Reviewed all 17 changed/added ticket files using `git diff --cached dc14db9897ff143a4c86cc77bbc17eddaf199de6`, plus `git diff` (empty) and the complete untracked inventory. HEAD still equals the fixed baseline and its commit range is empty; the staged implementation was the review target.

Standards sources: AGENTS.md, docs/agents/{domain,issue-tracker,triage-labels}.md, CONTEXT.md and ADR-0001 through ADR-0005. The stream sampler follows ADR-0003: current-position-to-EOF consumption, bounded head/tail/work arrays, caller-owned close/reset, explicit limit errors and source-controlled blocking. Its subtraction-based read bound and separate one-byte probe avoid long overflow. It feeds the existing feature/model pipeline and copies builder configuration into the instance. The explicit #7 deferral governs concurrent lifecycle behavior; this change retains and documents sequential use.

The test changes preserve the existing public behavior and restricted feature boundaries, share the inherited boundary fixtures, retain the fixed reference data, and add bounded generated-stream and caller-ownership coverage. No dependency, model, asset or reference changes appear. README, API documentation, runnable consumer and verification record match the inspected implementation.

Independently checked the saved Java 8/17/21 XML: each has 24 unit/contract and 13 integration tests, with zero failures, errors or skips. Logs corroborate real ORT, reference coverage, the 2,147,483,665-byte limited-heap stream and isolated consumer stream execution. This review did not rerun the matrix. All 24 protected paths retain their original SHA-256 and exact untracked inventory.

Standards findings: 0 hard violations; 0 actionable heuristic findings. Commit/push and CI for the final commit remain subsequent delivery gates.

## Spec

No Spec findings (0): no missing implementation requirement, incorrect behavior, or unrequested scope was found.

Reviewed all 17 staged files against `dc14db9897ff143a4c86cc77bbc17eddaf199de6`, the empty unstaged diff, and the full untracked inventory against issue #5, parent #1, the execution handoff and accepted ADRs. The 24 protected files still match their original paths and SHA-256 hashes.

`InputSample.fromStream` consumes remaining content through EOF using three fixed 4096-byte arrays and a long counter. Its circular tail preserves content order. Bounding reads by `maxBytes - length` and probing without incrementing the total safely handles `Long.MAX_VALUE`. Overflow stops after one excess byte with INPUT context; read failures retain their cause. The public entry checks null/closed state before reading and never closes or resets the source. Builder limits are immutable after construction.

Public API tests cover the required limits, ownership, failure, positioning and chunking contracts. Stream results match all 141 content and 207 path-content references in three modes, including every result field; restricted feature comparisons reuse the permitted existing boundary. The generated-stream probe retains fixed source storage and handles 2,147,483,665 bytes with actual heaps of 64,487,424/67,108,864 bytes and real ORT inference.

I independently parsed preserved Surefire/Failsafe XML and inspected runtime/build/offline logs: each Java 8/17/21 run has 24 unit/contract and 13 integration tests, zero failures/errors/skips, and all 28 baseline test methods retained. Strict Javadoc configuration and successful builds, reference coverage, generated-stream output and execution of the isolated stream example agree with the verification record.

Dependencies, assets and references are unchanged; no #6/#7/#8 implementation was added. Documentation covers ownership, replay, source-controlled timeout and sampling-memory exclusions.

Subsequent delivery gates remain: ticket-only commit/push, successful three-version CI for the final SHA, post-commit workspace/protected-file verification, and the final execution receipt. These were not claimed complete during this review.

Review totals: Standards 0 hard violations and 0 actionable heuristic findings;
Spec 0 findings. No acceptance-blocking review issue remains.

## Workspace preservation

The 24 original untracked files under `.scratch/`, AGENTS.md, CONTEXT.md,
docs/adr/, docs/agents/, docs/design-session.md and docs/upstream-findings.md are
protected by an initial NUL-delimited path snapshot and SHA-256 list, both stored
outside the worktree. Their contents and untracked status are checked before and
after the ticket commit; they are never staged. No ignore rules change.

No PR, merge, tracker edit, tag, Release, Central publication, other-repository
write, private-data upload or external message is part of this ticket. The final
commit and push to the existing repository are covered by the user's authorization.
Compatibility evidence remains limited to the tested inputs and Ubuntu 24.04 x64 / CPU.
