# Issue #2 verification record

Scope: [#2](https://github.com/zerocloud-sdk/magika/issues/2), under the approved
[parent specification #1](https://github.com/zerocloud-sdk/magika/issues/1).
The tracker was read before implementation: both issues had no comments and #2
had no native blocking dependencies. No issue was edited or closed.

## Reproduce

The acceptance entry point is `mvn verify`. Maven must run on JDK 21; Surefire and
Failsafe select the actual test JVM through `test.java.home`:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -B -ntp -Dtest.java.home=/usr/lib/jvm/java-8-openjdk-amd64 verify
mvn -B -ntp -Dtest.java.home=/usr/lib/jvm/java-17-openjdk-amd64 verify
mvn -B -ntp -Dtest.java.home=/usr/lib/jvm/java-21-openjdk-amd64 verify
mvn -B -ntp install
mvn -B -ntp -f examples/offline/pom.xml clean package
for runtime in 8 17 21; do
  bash scripts/verify-offline.sh "/usr/lib/jvm/java-$runtime-openjdk-amd64"
done
```

`verify` includes 11 unit/contract tests and 9 packaged-artifact integration
tests. The reference tests iterate all 9,261 official feature cases and all 47
HIGH_CONFIDENCE content cases (43 actual model calls, 4 rule-only results).
The 214-label rule boundary test makes 642 below/equal/above comparisons; it is
not a classifier accuracy test. No Python interpreter or generator is used.

## Execution evidence

Local environment, 2026-09-17: Ubuntu 24.04.4 x64, glibc 2.39, Maven 3.8.7;
compiler OpenJDK 21.0.11. The selected runtime is printed and asserted against
`java.home`; real native ORT reports `1.30.0` on each runtime.

| Runtime | Final `verify` | Isolated packaged consumer |
| --- | --- | --- |
| OpenJDK 8u492, build 25.492-b09 | Passed, 20 tests, no failures/skips | Passed |
| OpenJDK 17.0.19+10 | Passed, 20 tests, no failures/skips | Passed |
| OpenJDK 21.0.11+10 | Passed, 20 tests, no failures/skips | Passed |

All three final reference runs have maximum absolute score error
`9.5367431640625e-7` (limit `1e-5`); labels, reasons and MIME strings match exactly.
The standalone consumer reports `pdf`, `application/pdf`, score
`0.9922314882278442` after instance close on all three JVMs.
The tested SDK JAR and all three isolated consumers' SDK dependency have the
same SHA-256: `a43393499b2757ce4685e7f33d829482c34f0421b6812e89f0d5d88aa611dcd9`.

The isolated consumer runs in a fresh Linux network namespace (only its own
loopback interface) and a temporary chroot containing a JVM, native system
libraries and the consumer's packaged runtime dependencies. There is no Python,
Maven, host filesystem bind or external network interface. The consumer checks
the isolation conditions itself. A minimal chroot omits `/proc`; resulting JVM/ORT
CPU-discovery warnings do not prevent real inference. This is not a performance
measurement environment.

The 64 MiB complete-array probe runs with `-Xmx96m -XX:+UseG1GC`; a second full
array cannot fit. G1 is explicit because Java 8's default Parallel GC old
generation cannot hold the initial 64 MiB allocation at this heap size.

Native resource probes measure file descriptors, worker threads and RSS after
warmup, through public SDK calls. Each resource run includes 60 create/close
cycles and 6,000 further inferences on reused sessions. RSS is allowed 96 MiB
of allocator/JVM variation; exact RSS equality is not asserted. Native thread
probes verify default one-thread sessions and configured three-/four-thread
sessions, and verify workers disappear when their instances close.

| JVM | Post-warmup RSS samples, KiB | File descriptors before/after |
| --- | --- | --- |
| 8 | 115612, 132216, 134684, 140460 | 38 / 36 |
| 17 | 101612, 125708, 127800, 128068 | 21 / 19 |
| 21 | 104336, 122612, 131288, 132464 | 20 / 18 |

Failure cases include missing, truncated, oversized and bit-flipped model assets,
reordered class metadata, incompatible MIME metadata, a valid ONNX model with a
wrong input name, and actual native-loader failure. Each packaged failure is
repeated 30 times with file-descriptor checks. Wrong-signature assets are rejected
at the preceding SHA-256 gate; the valid bundled model is also checked against
the native input/output signature during every successful creation. The tests
do not weaken digest authentication to reach later checks.

## Acceptance mapping

| #2 AC | Implementation and authoritative verification |
| --- | --- |
| 1 | `pom.xml`: `net.zerocloud:magika:0.1.0`, JDK 21 enforcer, `--release 8`; `MagikaTest` asserts the actual JVM/native ORT, `PackagedIT` checks SDK and runtime dependency bytecode; CI has Ubuntu 24.04 Java 8/17/21 jobs. |
| 2 | `Magika.create`, `builder().build`, byte-array identification, provenance and sequential `AutoCloseable` use run through the public API. Native thread probes observe applied settings; `ModelAdapter` passes sequential graph execution before Session creation. All content references use the default HIGH_CONFIDENCE policy. |
| 3 | Three authenticated assets are in the main JAR. `asset-manifest.json` records sizes, fixed sources, digests, contract, Apache-2.0 license and both original test fixtures. `ModelInfo` separates SDK/model versions and exposes an immutable digest map. |
| 4 | `ModelAssets` validates exact bytes and corresponding metadata/order; `ModelAdapter.create` validates actual native signatures before returning. Faulted packaged JARs cannot create an instance. Creation cleanup covers Session and SessionOptions; native errors retain causes. |
| 5 | `ReferenceTest`: all 9,261 original 128/64/512 feature cases and 47 HIGH_CONFIDENCE content cases; `ModelBoundaryTest`: actual 1024/1024/4096/padding-256 boundaries, all byte whitespace values and 642 threshold boundaries; `MagikaTest`: strict UTF-8, empty, short and long whitespace. |
| 6 | Public immutable results expose every specified getter. Official labels/reasons/MIME and score tolerance are checked; raw scores survive both mapping and low-confidence fallback. |
| 7 | `undefined` explicitly becomes an empty Optional, score 1.0 and `isModelUsed=false`; unknown/empty are successful values. Public argument/lifecycle tests and packaged SDK-failure probes check the exception distinction, category, context and available cause. |
| 8 | Constrained-heap packaged-array probe, post-close result use, multiple-instance survival, native worker release and repeated resource probes. Per-call Tensor/Result use try-with-resources; instance cleanup attempts Session before SessionOptions and never closes the shared environment. |
| 9 | Original static Java-readable fixtures; no Python build/test/runtime dependency. Standalone Maven consumer plus actual network-namespace/Java-only-root execution on the packaged SDK. |
| 10 | README, strict Javadoc generation and standalone example cover the public contracts, ownership, score semantics, errors, version provenance, sequential closing, native loading, offline use and verified platform. No downstream APIs were implemented. |

## Review and workspace preservation

Review base is the empty **tree** `4b825dc642cb6eb9a060e54bf8d69288fbee4904`.
There was no initial commit, index entry or remote branch. The review command is
`git diff --cached 4b825dc642cb6eb9a060e54bf8d69288fbee4904`, not a three-dot diff.
Only explicit issue #2 files are staged; unstaged/untracked omissions are
checked separately. Both independent reviews completed before the implementation commit.

## Standards

Completed an independent, read-only review of all 33 staged additions against
the documented standards, glossary, applicable ADRs and code-smell baseline.
No documented-standard breaches or actionable heuristic smells were found.
Asset/reference digests, unstaged omissions and all original-file hashes were
independently checked. **0 findings; no outstanding standards issue.**

## Spec

Completed a separate, read-only review of all 33 staged additions against #2,
its approved parent requirements and the complete execution objective. No missing,
partial, incorrect or out-of-scope implementation was found. Native ownership,
upstream rules, public contracts, packaging, CI and executed evidence were checked.
**0 findings; no outstanding specification issue.**

Coverage limit: altered signatures/metadata fail at checksum validation. Cleanup
after native resources have been created is supported by direct code inspection
of ownership and exception paths, rather than those fault fixtures. Successful
sequential cleanup additionally has observable native resource probes.

Review totals: Standards 0, Spec 0; no outstanding issue in either axis.

## Preserved workspace baseline

The 24 original untracked planning files in `AGENTS.md`, `CONTEXT.md`, `docs/`
and `.scratch/magika-0.1.0/` retain their content and untracked status. No ignore
rule hides them. Their initial file list and SHA-256 manifest are kept outside the repo
in `/tmp/magika-issue2-audit/baseline.status` and `baseline.sha256`; the latter's
own SHA-256 is `1f7c8ad4f1e0777344d80b5ee8dc3d3cb2d2fc3190e04414ed5553a5feb7c20e`.
Local command logs and per-JVM Surefire/Failsafe reports are in the same audit
directory. These local planning files and audit snapshots are not added to the
implementation commit.

Verification is limited to Ubuntu 24.04 x64 / CPU and this ticket's byte-array
scope. It does not establish all-214-class accuracy, shared concurrent lifecycle
behavior, other input APIs/platforms, a throughput SLA, or formal release status.
