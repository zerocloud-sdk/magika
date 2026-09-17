# Issue #3 verification record

Scope: [prediction modes #3](https://github.com/zerocloud-sdk/magika/issues/3),
under the approved [parent specification #1](https://github.com/zerocloud-sdk/magika/issues/1).
The issue bodies and comments were reread before implementation. Issue #3 was
open, unassigned and without comments; its native blocker #2 was closed. Parent
#1 had no comments. No tracker state was changed.

The fixed implementation/review base is
`7033295664ea225a3e2aaa8ece0ff973c84badd3` on `main`. SDK `0.1.0`, model
`standard_v3_3`, ORT `1.30.0`, dependencies and fixed upstream commit
`9f225aa480e675af44343b9160f077073ed1b752` remain unchanged. The three model assets
and both original gzip fixtures retain their exact bytes and SHA-256 digests.
Only the manifest's reference acceptance scope changes to 141 cases, 47 per mode.

## Reproduce

Maven runs on JDK 21 and compiles with `--release 8`. Surefire, Failsafe and the
packaged integration probes actually execute the selected test JVM:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -B -ntp -Dtest=MagikaTest,ReferenceTest,ModelBoundaryTest test
mvn -B -ntp clean
for runtime in 8 17 21; do
  mvn -B -ntp -Dtest.java.home="/usr/lib/jvm/java-$runtime-openjdk-amd64" verify
  mkdir -p "target/verification/java-$runtime"
  cp -R target/surefire-reports target/failsafe-reports "target/verification/java-$runtime/"
done
mvn -B -ntp install
mvn -B -ntp -f examples/offline/pom.xml clean package
for runtime in 8 17 21; do
  bash scripts/verify-offline.sh "/usr/lib/jvm/java-$runtime-openjdk-amd64"
done
```

The offline script runs the separately compiled consumer from installed JARs
inside a Java-only chroot and a fresh network namespace. The consumer checks
isolation itself, verifies the existing default PDF result after instance close,
and executes all three modes on the same eight-byte ambiguous content. Python
and Maven are absent from that runtime. The ordinary Java build/test path has no
Python dependency.

## Execution evidence

Local environment, 2026-09-17: Ubuntu 24.04.4 x64 / CPU, glibc 2.39,
Maven 3.8.7; compiler OpenJDK 21.0.11. Each test run prints and asserts its actual
Java home and reports native ORT 1.30.0 after real inference.

| Actual runtime | `mvn verify` | Isolated packaged consumer |
| --- | --- | --- |
| OpenJDK 8u492, build 25.492-b09 | Passed: 13 unit/contract + 9 integration tests, no failures/errors/skips | Passed, default PDF + three modes |
| OpenJDK 17.0.19+10 | Passed: 13 unit/contract + 9 integration tests, no failures/errors/skips | Passed, default PDF + three modes |
| OpenJDK 21.0.11+10 | Passed: 13 unit/contract + 9 integration tests, no failures/errors/skips | Passed, default PDF + three modes |

All three final reference runs report maximum absolute score error
`9.5367431640625e-7`, below `1e-5`. Each also passes all 9,261 feature references,
the 1,284 threshold comparisons and the 15 zero-score cases. Strict Javadoc
generation and all nine existing packaged-artifact integration checks pass in
each `verify` run. Java 8 bytecode is checked in the SDK and runtime dependencies.

`mvn install` and the standalone consumer's `clean package` also pass. The tested
SDK JAR and the consumer's installed SDK dependency share SHA-256
`41baf1b9042e1ff77790dd1cdacf4c4734f807dc719b1303ab1ebc8870cce343`.
On all three isolated runtimes the PDF result is `pdf`, `application/pdf`, score
`0.9922314882278442`. The mode example preserves raw `wasm` and score
`0.31421634554862976` in all modes; HIGH/MEDIUM return `unknown` with
`LOW_CONFIDENCE`, while BEST returns `wasm` with `NONE`. Each consumer asserts the
expected label, MIME, raw prediction, reason and score tolerance. The minimal
chroot omits `/proc`; its CPU-discovery warnings do not prevent inference and
these isolated runs are not performance measurements.

`ReferenceTest` traverses every entry in the authenticated content fixture once,
dispatching through public `identify(byte[])` to the mode named by the fixture.
Unknown modes fail enum conversion; there is no skip branch. It asserts 141 total
cases and exactly 47 per mode, including 43 model results and four rule-only
results per mode. Raw/final labels, overwrite reasons and MIME strings are exact.
Each score is checked against `1e-5`, and each model result's score equals its raw
prediction's score exactly. `dl=undefined` explicitly requires absent raw
prediction, no model use, score 1.0 and reason `NONE`.

`ModelBoundaryTest` makes 642 below/equal/above comparisons in HIGH_CONFIDENCE and
642 in MEDIUM_CONFIDENCE, covering all 214 configured raw labels. An additional
15 deterministic zero-score cases cover both mappings, text/binary fallback,
unchanged-label `NONE`, and BEST_GUESS retaining mapped labels. These use the
existing package-private prediction contract; no public testing API was added.
The fixed upstream decision order was checked against
[`magika.py` at the pinned commit](https://github.com/google/magika/blob/9f225aa480e675af44343b9160f077073ed1b752/python/src/magika/magika.py#L577).

## Completion criteria and evidence

The row order follows the execution objective's 24 completion criteria.

| # | Requirement | Implementation / authoritative verification |
| --- | --- | --- |
| 1 | Three Builder modes | Public `PredictionMode` enum and `Builder.predictionMode`; every mode executes real inference in `ReferenceTest` and the standalone consumer. |
| 2 | HIGH defaults | `MagikaTest.defaultsAndReusedBuilderKeepTheirOwnPredictionModes` checks both `create()` and an unset builder on inputs whose outputs differ under other modes. |
| 3 | Built configuration is immutable | The same test builds default, MEDIUM and BEST instances, reuses the builder, changes it back to HIGH, then observes each instance's original behavior; the adapter stores a final enum value. |
| 4 | Invalid mode fails clearly | `invalidArgumentsAreNotIdentificationResults` asserts `IllegalArgumentException` and a mode-specific message for null; the enum limits non-null arguments to the three supported modes. |
| 5 | HIGH uses raw-label threshold and configured default | `ModelAdapter.resultForPrediction` passes the raw label to `ModelAssets.threshold`; HIGH boundary cases read all label-specific thresholds and defaults from the authenticated config. |
| 6 | MEDIUM uses uniform threshold | `ModelAssets.threshold` selects the configured 0.5 value; MEDIUM boundary cases cover all labels, including labels with higher HIGH thresholds. |
| 7 | BEST retains mapping without rejection | The adapter maps before its mode guard; zero-score `randomtxt` and `randombytes` cases retain `OVERWRITE_MAP`, and unmapped labels remain specific. |
| 8 | Upstream fallback/reasons | The adapter selects text/binary from the mapped label, gives low-score rejection precedence over mapping, and sets `NONE` when the final label equals raw. Content and deterministic cases verify the results. |
| 9 | All 141 public-API cases, 47 per mode | `ReferenceTest.all141ContentsMatchThroughPublicApiInEveryMode` checks total and per-mode counts and never skips entries. |
| 10 | Exact raw/final labels and reasons | Assertions for every content fixture entry, with explicit handling of raw `undefined`. |
| 11 | Absolute score error at most 1e-5 | Per-entry score assertion and maximum-error reporting in every JVM's `ReferenceTest` output. |
| 12 | MIME matches fixed metadata | Every reference and deterministic boundary result is compared with the authenticated content-type knowledge base. |
| 13 | Boundaries, mapping and fallback | HIGH/MEDIUM each execute 214 × 3 comparisons; 15 additional zero-score cases verify both mapped types, text/binary fallback and BEST behavior. |
| 14 | All modes share rule-only contract | `MagikaTest.rulesUseStrictUtf8AndKeepUnknownAndEmptySuccessful` loops over all modes for empty, valid/invalid short UTF-8 and long-whitespace rules; content references check all 12 `undefined` cases. |
| 15 | Raw top-1 score survives rewrites | `DetectionResult.getScore` still returns the immutable raw score; reference, boundary, builder-reuse and consumer assertions compare scores after both mapping and fallback. |
| 16 | Preserve #2 regressions | All 9,261 feature references, actual 1024/1024/4096 boundaries, six whitespace bytes, strict UTF-8, default behavior, ownership and existing packaged probes remain in `mvn verify`. |
| 17 | Three actual JVMs, ORT, packaging and Javadoc | Selected-JVM `verify` logs and separately retained Surefire/Failsafe XML; `PackagedIT` checks Java 8 bytecode, digests and real native behavior; Javadoc runs with doclint and warnings treated as errors. |
| 18 | Runnable mode example and offline consumer | `examples/offline` compiles against the installed SDK; `verify-offline.sh` executes default PDF plus all modes under each runtime in isolation. |
| 19 | API docs and manifest scope | README, `PredictionMode`, Builder, `DetectionResult` and `OverwriteReason` document selection, score and reason semantics; manifest records 141 cases and all three per-mode counts. |
| 20 | Model-upgrade acceptance and honest coverage | README lists feature, three-mode reference, threshold, rule-short-circuit, real JVM/ORT, packaging and consumer checks; it explicitly excludes an all-214-class accuracy claim. |
| 21 | Standards / Spec review of all ticket changes | Independent review reports below use the fixed base against the staged working result, explicitly including new files. |
| 22 | Issue-only commit after acceptance | Final execution receipt supplies the commit SHA; its parent is the fixed base and its file list is the explicitly reviewed ticket scope. |
| 23 | Preserve original workspace; no ticket leftovers | Original 24-file path/hash baseline and untracked status are compared after commit; tracked worktree and index must be clean. All command evidence is stored outside the repository. |
| 24 | Execution report | This per-criterion record, retained command/XML evidence and final receipt provide runtime versions, reference counts/error, reviews, commit and final workspace state. |

## Review

Review base: `7033295664ea225a3e2aaa8ece0ff973c84badd3`.
The implementation is uncommitted at review time, so the authoritative diff is
`git diff --cached 7033295664ea225a3e2aaa8ece0ff973c84badd3`, with
`git diff` and the untracked file list checked separately for omissions. The new
`PredictionMode.java` and this verification record are included explicitly.
An empty `<base>...HEAD` is not used as evidence.

## Standards

**0 findings:** no documented-standard violations or actionable Fowler baseline
smells. The independent reviewer inspected all 13 staged files, including both
additions, against the fixed base. Domain terminology, fixed asset distribution,
immutable configuration/results and the public-API/internal-adapter test boundary
remain consistent. The reviewer also confirmed no unstaged omissions, preservation
of the original 24 untracked files, unchanged fixed assets/fixtures and all three
retained 22-test JVM reports. No edits were made during review.

## Spec

**0 findings:** no missing/partial requirements, implementation errors or scope
creep within #3. The separate reviewer inspected all 13 staged files and checked
unstaged/untracked omissions. Three-mode selection, defaults, null rejection,
Builder immutability, upstream threshold/mapping/fallback order, raw scores,
rule-only results, all 141 references, deterministic boundaries, #2 regressions,
documentation and upgrade acceptance were verified. The reviewer independently
inspected all three JVM XML/log sets, Javadoc/build evidence and isolated consumers,
and confirmed fixed-asset and original-file preservation. No dependencies or
downstream features were added.

Review totals: Standards 0; Spec 0. Neither axis has an outstanding finding.

## Workspace preservation and evidence location

The original 24 untracked files under `.scratch/`, `AGENTS.md`, `CONTEXT.md`,
`docs/adr/`, `docs/agents/`, `docs/design-session.md` and `docs/upstream-findings.md`
are protected by the initial path and SHA-256 snapshots. No ignore rules change.
Their hash-list digest is
`1f7c8ad4f1e0777344d80b5ee8dc3d3cb2d2fc3190e04414ed5553a5feb7c20e`.
Snapshots, fetched issue/upstream sources, full command logs and independent
per-JVM Surefire/Failsafe reports are in `/tmp/magika-issue3-audit.pOOufA/`.
Immediately before commit, all 24 file hashes match and the untracked path list is
identical to the baseline. The index contains exactly the 13 reviewed issue files;
there are no unstaged changes. The final execution receipt supplies the commit SHA
and repeats the workspace checks after commit.

This ticket adds no Path, stream, batch, concurrent lifecycle, performance or
release features. No PR, tracker edit, push, tag, Release or Central publication
is part of this execution. Passing these reference and rule tests establishes
the documented compatibility cases, not accuracy across all 214 classes.
