# Issue #4 verification record

Scope: [regular-file identification #4](https://github.com/zerocloud-sdk/magika/issues/4),
under the approved [parent specification #1](https://github.com/zerocloud-sdk/magika/issues/1).
Both issues and their comments were reread before implementation: no comments,
#4 open and ready-for-agent, native blocker #3 closed. No tracker state changed.

Implementation and review base: `bbeff50b9ea016c06e09a5c75a8a0de5ca885557`
on `main`. SDK `0.1.0`, model `standard_v3_3`, ORT `1.30.0`, all dependency
versions and fixed upstream `9f225aa480e675af44343b9160f077073ed1b752` are unchanged.
The existing model, configuration, knowledge base, manifest and both original
feature/content gzip fixtures retain their exact bytes.

## Reproduce

Use JDK 21 to compile with `--release 8`; select the actual test runtime separately.
The verification user must be unprivileged so mode-000 files really deny reads.
The special-file probes run in killable JVMs with a 45-second timeout and forced
termination followed by a termination check. The existing native probes retain
300-second limits. No Python is used by Maven, Java tests, examples or the SDK.

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -B -ntp -Dtest=PathTest,PathReferenceTest,ReferenceTest,MagikaTest,ModelBoundaryTest test
# Keep evidence outside target so clean cannot remove previous runtime reports.
audit_dir=$(mktemp -d /tmp/magika-path-verification.XXXXXXXX)
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

## Reference and input evidence

The test-only `reference/path-manifest.json` records the original path-reference
source, 2,604-byte size and SHA-256
`9b7dedd1e1fbddf98a3ab6598fe2674deae5ba5597f6ba3bbc466737a8b357d4`.
It also records each of the 69 original files' exact upstream URL, byte size and
SHA-256. The files total 1,490,089 bytes. The manifest itself is authenticated as
`a7de97b903d30cd48cc34d07364635e988bca35996210f55831b0f39b23f1731`.
All sources use the pinned upstream commit. Retrieval was a one-time development
operation; the normal build consumes checked-in static bytes and never downloads
reference data. Test materials are excluded from the production JAR.

`PathReferenceTest` authenticates all files, consumes all 207 reference entries,
rejects duplicate paths within a mode and unknown modes, and checks each mode's
path set equals the manifest's complete 69-file set. Every entry goes through
public `identify(Path)` and is compared with its fixed reference and a separate
public `identify(byte[])` of the complete original file. Each mode has 68 model
results and one empty rule result. Raw/final labels, MIME, reasons, model version,
model-use flag and raw prediction presence are checked; scores use the fixed
`1e-5` reference tolerance and exact equality between both Java entry points.
`dl=undefined` requires absent raw prediction, score 1.0, no model and reason NONE.

`PathTest` compares full results and exact model features for 63 boundary inputs
in all three modes (189 comparisons): empty/short content, 7/8 model threshold,
1024/2048/4096/8192 window boundaries, leading/trailing and long whitespace, high
unsigned bytes, valid/invalid strict UTF-8, and sequences complete or split at the
4096-byte boundary. Result tests use the public API with real files. The extra
feature comparison uses the already permitted restricted feature contract.
Null Path, readable symbolic links, retained immutable results and calls after
close (including a missing path) are separately checked. The byte[] null tests
retain an explicit byte[] cast after introduction of the overload.

`PathProbe` runs against the packaged SDK, not target/classes. It checks real
regular files, links to files, links to directories/FIFOs, directories, /dev/null,
/dev/zero, a real FIFO, a missing path, a broken link and mode-000 permissions.
The permission check first observes a real `AccessDeniedException` from NIO open,
then requires the same cause class through the public SDK. No permission or
special-file case is skipped. An observer at the public NIO provider boundary
also proves the stable special files are rejected without any content open.

The same test-only observer delegates to real files and channels. It coordinates
an actual truncation after the SDK has opened/sized the file, and a real channel
closure immediately before reading, yielding EOFException and ClosedChannelException.
Both must become unchecked INPUT failures with the exact supplied path context
and original cause class. Observed opens/reads/closes prove no reopen or retry.
Deleting and replacing a pathname during sampling still returns the opened
original's result, proving both windows use one handle. Short reads (13 bytes per
read) still produce the complete expected sample and result through that handle.
These controlled provider events use no SDK private fields or methods, arbitrary
sleeps or public testing API. They are narrowly scoped error/ownership tests;
reference, boundary, permission and large-file cases also exercise ordinary paths.

The resource probe warms success/failure paths, then measures /proc/self/fd after
three rounds of 100 successful identifications, 100 real truncation failures and
100 closed-channel failures. It explicitly checks no handles target the input or
its deleted predecessor after calls, without using GC to hide leaks. Native
model/resource regression probes remain part of the same matrix.

The large-file probe creates a sparse 5,368,709,137-byte ordinary file (over 5 GiB
and Integer.MAX_VALUE), uses a separate JVM with `-Xmx64m`, observes one open/close
and only 8,192 sampled bytes, and compares the real ORT result with a complete
8,192-byte representative having the same head/tail windows. A second call uses
the ordinary Path directly. This verifies bounded sampling and long offsets;
it makes no file-snapshot or all-concurrent-modifications promise.
## Execution evidence

Environment on 2026-09-17: Ubuntu 24.04.4 x64 / CPU, glibc 2.39, Maven 3.8.7;
compiler Ubuntu OpenJDK 21.0.11 with Java 8 release targeting. The test JVM home
is asserted, not inferred from the compiler or environment. Every runtime reports
native ORT 1.30.0 and real PDF inference with score 0.9922314882278442.

| Actual runtime | `clean install` | Isolated packaged consumer | Path descriptor samples | Actual large-file max heap |
| --- | --- | --- | --- | --- |
| OpenJDK 8u492, build 25.492-b09 | Passed: 16 unit/contract + 12 integration; 0 failures/errors/skips | Passed: default PDF, all modes, saved upload | 36, 36, 36, 36 | 64,487,424 bytes |
| Ubuntu OpenJDK 17.0.19+10 | Passed: 16 unit/contract + 12 integration; 0 failures/errors/skips | Passed: default PDF, all modes, saved upload | 19, 19, 19, 19 | 67,108,864 bytes |
| Ubuntu OpenJDK 21.0.11+10 | Passed: 16 unit/contract + 12 integration; 0 failures/errors/skips | Passed: default PDF, all modes, saved upload | 18, 18, 18, 18 | 67,108,864 bytes |

All three runs pass all 207 path references, 141 content references and 9,261
feature references. Maximum absolute path score error is
`7.152557373046875e-7`; maximum content score error is `9.5367431640625e-7`.
Path/byte[] comparisons have exactly equal scores and result fields. Each run
also passes the 189 exact boundary comparisons, 1,284 threshold comparisons and
15 zero-score cases. Strict Javadoc generation (doclint all, failOnWarnings) and
the packaged-artifact checks pass in every clean install. The packaged SDK and
its runtime dependencies have Java 8 compatible base bytecode.

Each large-file run reports 5,368,709,137 bytes, 8,192 sampled bytes, one open and
one close. Its actual result is raw `randombytes`, final `unknown`, score
`0.9906219244003296`, reason `OVERWRITE_MAP`, with real model use. Every handle
probe reports zero remaining descriptors for the input, after 300 successes,
300 truncations and 300 closed-channel failures. The existing native resource
probe also passes: post-warmup RSS KiB samples are
`[117112, 134836, 133628, 140564]` on 8,
`[106972, 127844, 125860, 132776]` on 17 and
`[111112, 120392, 125464, 123932]` on 21, within its existing 96 MiB tolerance;
native probe file handles end below their baseline in all runtimes.

The independent consumer's `clean package` succeeds using the installed SDK.
All three built SDK JARs and the consumer's installed SDK dependency have SHA-256
`175a37cd4dfe992c142fd3cadbcea63ddde366e217eb2be649475a15a1279648`.
The existing isolation script runs the consumer in a Java-only chroot and fresh
network namespace, with no Python or Maven. It checks the absence of external
network interfaces itself. The saved upload is 64 bytes and returns `pdf`,
`application/pdf`, score `0.9922314882278442` on every runtime, checked after SDK
close. The three-mode example also passes with raw `wasm`: HIGH/MEDIUM fall back
to `unknown`, BEST retains `wasm`, preserving score `0.31421634554862976`.
The existing minimal-root CPU-discovery warnings remain harmless to these
inferences; isolated consumer runs are not performance measurements.

## Completion criteria and authoritative evidence

Rows follow all 30 items in the execution objective. Final commit/state evidence
is supplied in the execution receipt after the reviewed commit is created.

| # | Requirement | Evidence |
| --- | --- | --- |
| 1 | Public Path API / immutable result | `Magika.identify(Path)` returns the unchanged immutable DetectionResult; PathTest checks use after close. |
| 2 | Full-result equality in every mode | All 207 original-file Path/byte[] comparisons and 189 boundary comparisons assert every result field and both scores. |
| 3 | Fixed reference and all original files | Authenticated path manifest, reference gzip, all 69 file hashes/sizes and pinned source URLs in PathReferenceTest. |
| 4 | All 207 entries / 69 per mode | Every fixture entry runs through the public Path API; total/per-mode counts and complete unique path sets are asserted, with no skips. |
| 5 | Exact raw labels | Shared `Fixtures.assertReference` checks raw labels for each result, including explicit absent-raw handling. |
| 6 | Exact final labels | Each final label equals the fixed reference output. |
| 7 | Exact overwrite reasons | Each reason equals the reference enum; mapping and low confidence still use the existing adapter. |
| 8 | Scores within 1e-5 | Per-entry assertions and maximum-error logs from each JVM, recorded above. |
| 9 | MIME from fixed metadata | Each result is compared with the fixed content-type knowledge base; its production digest remains unchanged and validated. |
| 10 | Rule-only contract | Reference empty cases assert no raw prediction, score 1.0, no model use and NONE; PathTest compares short/UTF-8/whitespace cases with existing rule regressions in all modes. |
| 11 | Follow links / regular files only | File and directory/FIFO symlinks use public Path; `readAttributes` follows links before a regular-file check. |
| 12 | All named input errors / real denial | Packaged PathProbe covers directory, devices, FIFO, missing and broken links; mode-000 is proven denied by both NIO open and SDK. |
| 13 | No blocking special-file content open | Observer asserts zero content opens; ordinary special Paths are also called in a killable JVM with 45-second timeout and termination check. |
| 14 | One handle / random windows | InputSample opens one SeekableByteChannel; large-file observer counts one open, and replacement during sampling retains the original result. |
| 15 | Bounded memory / no total-size ceiling | Sparse 5,368,709,137-byte regular file, -Xmx64m separate JVM, 8,192 observed bytes read, correct real ORT result and complete-sample parity. |
| 16 | Release handles on success/failure | Try-with-resources closes before inference; probes check one close and no target handles, plus stable descriptor samples after 900 calls without GC. |
| 17 | Truncation/read error category/context/cause | Coordinated real truncation and closed-channel I/O yield INPUT MagikaException with exact Path context and EOFException/ClosedChannelException causes. |
| 18 | No unknown / no automatic retry on failure | Error helper fails on any returned result; observed failed calls have one open, one failed read, one close. |
| 19 | null Path | PathTest asserts IllegalArgumentException; byte[] null regression remains typed and present. |
| 20 | Closed Path calls | PathTest asserts IllegalStateException for both a regular file and nonexistent Path after close. |
| 21 | Exact boundary features | 189 Path/byte[] feature comparisons cover empty, short, window, whitespace and strict UTF-8 boundaries, plus full-result parity. |
| 22 | Existing feature/content/threshold/ORT regressions | Full matrix retains 9,261 exact features, 141 content references, 1,284 below/equal/above comparisons, 15 zero-score cases and all nine previous integrations. |
| 23 | Saved-upload example runs | SavedUploadExample saves/closes upload input, calls identify(Path), checks PDF result after SDK close and cleans its file; independent consumer compiles and executes it. |
| 24 | Scope/ownership/stability documentation | README and public identify(Path) Javadoc document links, regular files, owned handle, bounded windows, no ceiling, stable path/content responsibility, no snapshot and no guarantee of detecting every concurrent change. Sequential-use limitation remains explicit. |
| 25 | Actual JVM matrix / Java 8 artifact / Javadoc | Retained per-JVM clean-install logs and XML, actual JVM assertions, Java 8 class checks for SDK/dependencies, real ORT 1.30.0, packaged assets and strict doclint. |
| 26 | Independent no-network/no-Python consumer | Existing standalone POM consumes installed JARs; each isolated Java-only chroot/network namespace runs default, all modes and saved-upload Path examples. |
| 27 | Standards and Spec review of complete changes | Fixed-base staged diff, separate unstaged and untracked inventory; independent code-review results below include all ticket additions. |
| 28 | Issue-only local commit after acceptance | Final receipt supplies SHA; its parent must equal the fixed base and its files must equal the reviewed ticket scope. |
| 29 | No leftovers / protected originals unchanged | Post-commit clean tracked/index diff and exact original 24-file untracked path/hash comparison; no ignore-rule changes. |
| 30 | Complete execution report | This per-criterion record plus final SHA/worktree receipt; retained full logs, XML, JVM evidence, reference counts/errors, resource observations and independent review results. |

## Review scope

The code-review workflow ran Standards and Spec as independent reviewers against
`git diff --cached bbeff50b9ea016c06e09a5c75a8a0de5ca885557`, covering all 91 staged
ticket files. HEAD still equalled the base during review, so the empty
`base...HEAD` comparison was not substituted for the actual work. Reviewers also
checked unstaged changes and the complete untracked inventory for omissions.
Only the original 24 protected files remained untracked. The reports below are
kept separate; neither axis has an outstanding finding.

## Standards

Hard violations: none. Actionable smell judgments: none.

Reviewed the nonempty staged diff against `bbeff50b9ea016c06e09a5c75a8a0de5ca885557`: 91 ticket files. HEAD still equals the fixed base, so the empty commit comparison was not used as the review scope. The unstaged diff is empty. All 24 untracked files remain the protected originals, and all protected-file SHA-256 checks pass.

Standards checked: AGENTS.md, docs/agents/domain.md, docs/agents/issue-tracker.md, docs/agents/triage-labels.md, CONTEXT.md and all five accepted ADRs. No additional CONTRIBUTING/coding-standard file was found. The change preserves domain terminology, fixed model/asset/dependency versions, Java 8 APIs, offline asset distribution and the ordinary-file sampling scope. Concurrent lifecycle support remains explicitly deferred by the authorized ticket boundary.

InputSample provides the shared bounded input representation required by the two public entry points; Features and ModelAdapter retain shared extraction, short-content and result rules. No speculative public abstraction or private SDK test hook was added. ObservedFile's delegation serves the public NIO provider boundary and real filesystem fault/ownership observations; removing that boundary would weaken the specified verification, so it is not an actionable Middle Man or Refused Bequest judgment.

The 69 original files are fixed test data rather than authored code. Their actual bytes pass the manifest SHA-256 checks; the manifest/reference digests, pinned upstream sources and 207-case coverage assertions were inspected. Tests primarily use public identify(Path), real files and real ORT; exact feature comparisons additionally use the permitted restricted feature contract.

The final staged Execution evidence section was checked against matrix-summary.json, all three runtime XML/log sets, consumer-build/offline logs and actual JAR hashes. Java 8/17/21 each pass 16 unit/contract and 12 integration tests without failures, errors or skips. Reported reference counts/errors, 189 boundary comparisons, bounded large-file sampling, descriptor/RSS observations and all isolated saved-upload results match their evidence. All four SDK JAR hashes match the reported value. No new standards finding arises from this documentation addition. The local commit and final worktree receipt remain the parent agent's completion gates.

Standards findings: 0 hard violations, 0 actionable smell judgments.

## Spec

No missing/partial implementation, scope creep, or incorrect implementation findings.

Reviewed the nonempty `git diff --cached bbeff50b9ea016c06e09a5c75a8a0de5ca885557` across all 91 ticket files, plus the unstaged diff and complete untracked inventory. HEAD still equals the fixed base and the commit list is empty; the empty committed diff was not used as the review. All 24 protected originals remain untracked and pass their initial SHA-256 checks.

The public Path overload returns the existing immutable result and shares the byte-array rule/features/model pipeline. It validates regular-file attributes while following links, samples bounded windows with long offsets through one owned channel, and preserves INPUT category, path context and available I/O causes without retrying. Existing byte-array null coverage remains explicit.

The authenticated fixture manifest and all 69 original-file hashes pass. Tests execute all 207 public Path references (69 per mode), checking raw/final labels, MIME, reasons, both scores, model version/use and absent raw predictions, with exact complete-byte-array parity. Exact boundary-feature/full-result comparisons cover 189 cases. Existing 9,261 feature, 141 content, threshold and native regressions remain covered.

Read the completed Java 8/17/21 matrix logs and reports: each has 16 unit/contract tests and 12 integrations, with no failures, errors or skips. Path score error is at most 7.152557373046875E-7. Real permission denial, pre-open special-file rejection, killable process deadlines, 5,368,709,137-byte/-Xmx64m sampling, replacement/short-read ownership, actual truncation/read failures and stable descriptor samples provide the requested behavioral evidence. The independent isolated consumer executes the saved-upload Path example on all three JVMs.

README/Javadoc retain the file-stability, no-snapshot and sequential-use boundaries. No later-ticket APIs, dependency/asset upgrades or publication work were added.

The final Execution evidence section of `docs/verification-issue-4.md` agrees with the retained logs/XML. Independently checked SDK JAR hashes match across all three builds and the consumer dependency.

Completion gates remain the final execution/review receipt, acceptance of both review axes, the requested local commit, and post-commit verification of ticket scope, clean tracked state and protected originals. These intentionally follow review and are not implementation defects.

Review totals: Standards 0 hard violations and 0 actionable smell judgments; Spec 0 findings.

## Workspace preservation

The 24 original untracked files under `.scratch/`, AGENTS.md, CONTEXT.md,
docs/adr/, docs/agents/, docs/design-session.md and docs/upstream-findings.md are
protected by the initial path/SHA-256 snapshot. Its own digest is
`1f7c8ad4f1e0777344d80b5ee8dc3d3cb2d2fc3190e04414ed5553a5feb7c20e`.
Their contents and untracked state are checked before and after commit. No
ignore rules change. Full command logs, per-runtime Surefire/Failsafe XML, fetched
issues, protected-file snapshot, reviews and final receipt are retained in
`/tmp/magika-issue-4-evidence/` outside the worktree.

This ticket adds no stream, batch, concurrent lifecycle, performance benchmark or
release feature. Model assets, dependencies and existing reference bytes are
unchanged. No tracker edit, PR, push, tag, Release, Central publication, other
repository write or external message is part of this execution. Compatibility
evidence remains limited to the tested inputs and Ubuntu 24.04 x64 / CPU.
