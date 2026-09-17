# Issue #10 — published 0.1.0 and Central consumption

The formal release of `net.zerocloud:magika:0.1.0` is **PUBLISHED** on Maven
Central. This record accepts [#10](https://github.com/zerocloud-sdk/magika/issues/10)
and maps all 16 acceptance items in [parent #1](https://github.com/zerocloud-sdk/magika/issues/1).
Neither issue was edited, commented on or closed by this task.

## Release identity

- Release source: `c409ddf8fc8c3d53ce65ed73c2b06a18845f7366`.
- Fixed review BASE: `65cb8714f42be2632548c2a28513371b070baf5a`.
- [Formal workflow 35286463752](https://github.com/zerocloud-sdk/magika/actions/runs/35286463752):
  `workflow_dispatch`, version `0.1.0`, exact source above, **mode=publish**;
  the eight source/verification/signing/consumption jobs passed on attempt 1;
  the failed publish job was resumed on attempt 2 after checking the same deployment,
  and the final run succeeded. No gate was skipped or rerun to conceal a test failure.
- [Tag and GitHub Release v0.1.0](https://github.com/zerocloud-sdk/magika/releases/tag/v0.1.0)
  resolve to the release source, as does the Release target. Its complete body
  matches the source's deterministic generator, including SDK/model versions,
  fixed upstream and asset URLs/digests, verified platform and API usage.
- Central deployment: `34e3277a-dd34-4e9c-8dd9-6bebc3932300`; authenticated status **PUBLISHED**,
  matching deployment ID/name and all public bytes; coordinate-specific authenticated
  published flag is `true`. Portal omitted PURLs, as documented below.
- [Durable ledger](https://github.com/zerocloud-sdk/magika/tree/01d053d6f184e339363a9ee4daf1741536c7fff1/releases/0.1.0):
  phase `complete`, candidate manifest, public signer key, original signed ZIP
  and deployment identity all preserved. Bundle SHA-256: `b1140610d9aaf56b07a5a84f346988e99f9fbe648256f6cfe91497cd177b2821`.

The source descends from every #2–#9 implementation/evidence commit. #10's
preparation changes only release tooling, examples and documentation: no change
to production source/resources, POM, tests, benchmark code, dependencies or
numerical/resource limits relative to BASE. The subsequent acceptance-evidence
commit updates main/README; it is deliberately distinct from the release source.
No published coordinate or existing release tag was overwritten, and no deployment
or ledger was deleted. Authenticated preflight found no earlier 0.1.0 deployment,
published coordinate, tag, Release or ledger. Only one formal workflow run and one upload were used; the publish job has two attempts.

The [machine-readable receipt](releases/issue-10.json) and
[raw evidence archive](releases/issue-10-verification.tar.gz) retain the identities,
original XML, transfer/runtime/build logs, official downloaded files and signatures,
candidate bundle, performance comparison and review records. `SHA256SUMS` inside
the archive authenticates every other archived file by digest. All signing
material in the archive is public; credentials are absent.

## Publication interruption and recovery

Attempt 1 passed all build/signature/consumer gates, uploaded deployment
`34e3277a-dd34-4e9c-8dd9-6bebc3932300`, observed `VALIDATED`, compared all eight
deployment files, and requested publication. While that same deployment was
`PUBLISHING`, Central returned `purls: []` with `Deployment components info not found`.
The strict coordinate check stopped the observer; the durable phase remained
`publishing`, with source, signed bundle and deployment ID intact.

Authenticated read-only polling continued against that ID. No upload, replacement,
deployment deletion, new signature, version change or tag movement was attempted.
The recorded HTTP-boundary reproduction isolates the empty-PURL response, and a
separate recovery probe verifies that the existing `publish()` path finishes the
same candidate after Central reaches `PUBLISHED`, without another upload or publish
request. The source's identity checks were not weakened.

Central still returned an empty PURL list after reaching `PUBLISHED`; the identity
was instead proven by the known deployment ID/name, the coordinate-specific
authenticated published flag, and all eight exact public files (including the POM).
After authenticated `PUBLISHED`, the published flag and all eight
public files were confirmed, only the failed publish job was rerun with
`gh run rerun 35286463752 --repo zerocloud-sdk/magika --failed`. The existing ledger
recovery completed the matching tag/Release. Attempt-1 failure logs and JSON,
timestamped deployment observations, the diagnostic red/green probes and the
successful recovery attempt are retained. The successful result is not presented
as an uninterrupted first attempt. Future observers should retain the same
identity and wait for complete external evidence when Central omits component info.

## Model and distribution

Model `standard_v3_3`, fixed upstream
`9f225aa480e675af44343b9160f077073ed1b752`, Apache-2.0:

| Asset | SHA-256 |
| --- | --- |
| [model.onnx](https://raw.githubusercontent.com/google/magika/9f225aa480e675af44343b9160f077073ed1b752/assets/models/standard_v3_3/model.onnx) | `fe2d2eb49c5f88a9e0a6c048e15d6ffdf86235519c2afc535044de433169ec8c` |
| [config.min.json](https://raw.githubusercontent.com/google/magika/9f225aa480e675af44343b9160f077073ed1b752/assets/models/standard_v3_3/config.min.json) | `ae24c742205358f6ff6dfd5facb6743fb69743dbba8373e73da58ff0cbd695db` |
| [content_types_kb.min.json](https://raw.githubusercontent.com/google/magika/9f225aa480e675af44343b9160f077073ed1b752/assets/content_types_kb.min.json) | `75208adba69bc0556403b62b32ef3ccf8b5ce494411780845852e52e6583a10d` |

All four published files below, and **all four detached signatures**, were fetched
from `https://repo.maven.apache.org/maven2/net/zerocloud/magika/0.1.0/` and matched
the recorded signed candidate byte-for-byte. Four actual signatures were verified
against primary fingerprint `C5149FD6B5EF7C2126F1FD0FCC1A12E348E171D8` in a new,
public-key-only GnuPG home. The workflow also compared validated deployment bytes
before triggering formal publication and waited for all eight public files.

| Central artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| [magika-0.1.0-javadoc.jar](https://repo.maven.apache.org/maven2/net/zerocloud/magika/0.1.0/magika-0.1.0-javadoc.jar) | 172460 | `6b3469187bdb03e978b8e289ed44ddb8f0684c7f8eee6f769976fd6e5cf50470` |
| [magika-0.1.0-sources.jar](https://repo.maven.apache.org/maven2/net/zerocloud/magika/0.1.0/magika-0.1.0-sources.jar) | 45033 | `d5493ab816bba5eab4c7aa633c1d9afeff8c43618318915f1e507fc768dd2c1c` |
| [magika-0.1.0.jar](https://repo.maven.apache.org/maven2/net/zerocloud/magika/0.1.0/magika-0.1.0.jar) | 2978079 | `f1332489436cd33ce3daa50952090d6c0c3bf525d099a41fb208733c5350cd33` |
| [magika-0.1.0.pom](https://repo.maven.apache.org/maven2/net/zerocloud/magika/0.1.0/magika-0.1.0.pom) | 7063 | `8bbb2fb0ad0b4a7a13a9d79374cc6def2d7e52d6c0b5427959cf3b7cdd0bfb7b` |

Main/sources/Javadoc/POM inspections passed: Java 8 classes and runtime dependency
bytecode, exact model assets, public API source/Javadoc coverage, LICENSE/NOTICE,
POM publication metadata and fixed dependencies. Test materials, verification
tools and Python are excluded from production artifacts. SDK/model versioning
and the supported label vocabulary remain as documented in the README.

## Final source gates

Ubuntu 24.04 x64 / CPU; compiler **Eclipse Temurin 21.0.12.1+1-LTS**; ORT **1.30.0**.
These are the actual JVMs recorded in the formal run's XML and runtime logs:

| Actual runtime | Unit tests | Packaged integration tests | Signed isolated consumer |
| --- | ---: | ---: | --- |
| 1.8.0_504-b01 | 39 | 17 | Passed |
| 17.0.20.1+1 | 39 | 17 | Passed |
| 21.0.12.1+1-LTS | 39 | 17 | Passed |

Each JVM has zero failures, errors or skips. Counts were independently summed
from the original Surefire/Failsafe XML, including individual test cases, rather
than inferred only from a green Actions conclusion. All three produced identical
main/sources/Javadoc/POM bytes. All three subsequently verified and consumed the
same signed candidate in separate empty Maven caches, with Java-only network
isolation. All **28** release/signature/recovery tests and supported-vocabulary
checks passed; no test was bypassed or assertion relaxed.

Each matrix run executed all **9,261** exact feature references, **141** content
references and **207** path references (69 per mode), plus batch reference parity.
Reference labels, raw-prediction presence/labels, overwrite reasons, MIME and model
metadata retain exact comparisons; scores retain the absolute **1e-5** limit.
Maximum observed content error was `7.748603820800781e-7`; path/batch error was
`5.364418029785156e-7`. These are reference-compatibility results, not an accuracy
claim covering every class or full score vector.

Input, batching, concurrent-close and native-failure probes all passed. The
independent `-Xmx64m` probes completed a **2,147,483,665-byte generated stream**
and **1,000,003 lazily generated paths**, retaining one current result. Raw logs
preserve RSS/descriptor observations, real permission/truncation failures,
release-failure paths and native tensor/session behavior. Local preparation also
passed 39 unit/17 integration tests on Java 8 and both updated isolation modes.

## Direct Central consumers

After publication, three new projects were copied outside the SDK checkout,
each with a nonexistent work directory and its own empty dependency cache. No
reactor or candidate `install-file` step was used. Explicit global/user settings
route all Maven traffic to Central; successful POM/JAR transfer lines and cache
origin records prove actual resolution from that URL.

| Actual consumer JVM | Empty-cache Central build | Java-only first run | Offline Maven build | Java-only offline run |
| --- | --- | --- | --- | --- |
| 1.8.0_492-8u492-ga~us2-0ubuntu1~24.04.1-b09 | Passed | Passed | Passed | Passed |
| 17.0.19+10-1-24.04.2-Ubuntu | Passed | Passed | Passed | Passed |
| 21.0.11+10-1-24.04.2-Ubuntu | Passed | Passed | Passed | Passed |


All consumers loaded the SDK JAR with SHA-256 `f1332489436cd33ce3daa50952090d6c0c3bf525d099a41fb208733c5350cd33`; each Java process
hashed the actual `Magika` code-source JAR. Logs prove byte[], Path and stream
identification, all prediction modes, ordered batch outcomes and partial-delivery
errors, shared-instance inference and use of retained results after close.

For each project, the first run used a Java-only chroot with networking unchanged;
the same project/cache then rebuilt with Maven `-o` and ran again in a new network
namespace without external interfaces. The isolated filesystem has no Python,
Maven, shell, SDK source checkout or host filesystem bind. Both executions use
real ORT/model inference. The chroot's absent `/proc` and CA bundle cause recorded
native CPU/telemetry warnings; assertions still pass. These runs are functional
acceptance, not performance measurements.

`central-java-*/` in the archive includes example sources/POM, settings, Maven
transfer/offline logs, cache origin, JVM output and `central-downloads.json`.
`central-downloaded-files/` preserves one complete official download; every
runtime's recorded downloads matched it and the signed candidate.

## Performance provenance and historical limit

The [#8 v1 report](performance/issue-8-v1.md) and its original raw measurements
are reused with a checked byte-level equivalence argument, not relabelled as a
new benchmark. The measured JAR was
`0b6100573efff99e49b2c2288a820a72bcac25e9ce09d17c47cf4f029df27ca3`;
the published JAR is `f1332489436cd33ce3daa50952090d6c0c3bf525d099a41fb208733c5350cd33`.

All **27 class files** and all runtime assets/resources are identical. The only
changed JAR entry is `META-INF/maven/net.zerocloud/magika/pom.xml`: #9 added release
metadata and packaging/Javadoc configuration. The only changed source files in
#8's source fingerprint set are that POM and documentation-only `package-info.java`.
All benchmark code, measured dependency bytes (ORT/Gson/Error Prone annotations),
model assets, stable LayerNorm adaptation and execution configuration are unchanged.
`performance-equivalence.json` records old/new per-entry and source digests;
`performance/measured-magika-0.1.0.jar` retains the authenticated measured artifact.

The performance numbers retain #8's original OpenJDK 21.0.11, host, corpus,
warmup, GC/heap, batching and concurrency limits; they are not Temurin remeasurements
or a QPS SLA. Final gates independently revalidate the unchanged resource bounds.
The [historical #6 descriptor assertion](verification-issue-6.md) still has no
established root cause. Subsequent passing probes, including this release, are
not a claim that it was fixed. No sustained-growth production defect was identified
by #8 or by the final gates.

## Parent specification: all 16 acceptance items

All report paths below refer to the raw archive's `java-8`, `java-17` and `java-21`
directories. Test names are also enumerated in the machine-readable receipt.
The test implementation at the release source was inspected to confirm each
assertion's scope, field precision and unchanged limits.

| # | Parent acceptance | Inspectable evidence |
| --- | --- | --- |
| 1 | Fixed asset/reference/original-file provenance; reject corrupt or incompatible assets | Source asset/path manifests; `PackagedIT.distributionContainsAuthenticatedAssetsAndJava8Classes`, `corruptedMissingAndIncompatibleAssetsCannotInitialize`, `aValidOnnxModelWithTheWrongSignatureIsRejected`; authenticated gzip/source files in reference suites. |
| 2 | Exactly 9,261 feature cases; actual 1024/4096 boundaries | `ReferenceTest.all9261OfficialFeaturesMatchExactly`; `ModelBoundaryTest.actualModelFeaturesUse1024PerEndAndUnsignedBytes` and `strippingNeverEscapes4096ByteWindows`; XML/log counts. |
| 3 | All modes: 141 content, 207 paths/originals, exact fields and <=1e-5 score | `ReferenceTest`, `PathReferenceTest`, batch reference cases; `Fixtures.assertReference/assertEquivalent` inspected at the immutable source; per-JVM maximum errors above. |
| 4 | Explicit rule-result semantics and absent raw prediction | `MagikaTest.rulesUseStrictUtf8AndKeepUnknownAndEmptySuccessful`; all content references map upstream `undefined` to absent raw prediction, score 1.0 and modelUsed=false. |
| 5 | Empty/short/whitespace/UTF-8/threshold/mapping/fallback boundaries | `MagikaTest`, all five `ModelBoundaryTest` cases, including every-label below/equal/above thresholds in HIGH/MEDIUM and BEST_GUESS mapping. |
| 6 | Equivalent Path/bytes/stream, chunking/short reads/position/ownership/limits | `PathTest`, all eight `StreamTest` cases, `PathReferenceTest` and content reference comparisons; exact/exceeded/zero/default-limit checks. |
| 7 | Regular/symlink/special/missing/permission/read/observable truncation | `PackagedIT.specialAndUnreadableFilesFailWithoutBlocking`, `singleFileHandleSurvivesReplacementAndClosesOnReadFailures`, Path/Batch tests; original probe logs record real denial, FIFO rejection and truncations. |
| 8 | Lazy ordered bounded batches, duplicates/errors/abort/progress/interruption | All ten `BatchTest` cases; `PackagedIT.realBatchShapesNativeFailuresAndResourceRelease`; actual native tensor shapes and failure causes retained. |
| 9 | Real shared Session; close draining/rejection/reentrancy/interrupts/resource failures | All five `ConcurrencyTest` cases; `PackagedIT.realSharedSessionAndNativeLifecycleFailures`; real ORT lifecycle/failure probe output. |
| 10 | Independent bounded-heap huge stream/lazy paths and resource trends | Packaged generated-stream/million-path/resource tests on each JVM; unchanged memory/descriptor limits; #8's 30+200 lifecycle observations and explicit historical limit above. |
| 11 | Python-free regular build; actual Java 8/17/21 ORT on Ubuntu x64 | Actual XML JVM properties/runtime logs; all final formal matrix jobs and both isolated consumer paths; no Python step in either workflow. |
| 12 | Reproducible throughput/latency/memory report tied to implementation | #8 v1 report, raw CSV/logs/source/artifact hashes; published/measurement byte equivalence and unchanged runtime dependency hashes. |
| 13 | Public usage/Javadoc: ownership/results/errors/close/offline/versions/platform | README, public source/package Javadoc, standalone examples; strict Javadoc and public coverage gate; final Release body and Central consumer logs. |
| 14 | Rehearsal checks all four artifacts/licenses/attribution/signatures; clean production | #9 rehearsal record plus this source's artifact inspections, three identical builds, actual four signatures, validated/public byte comparisons and signed consumption. |
| 15 | Public source/tag/Release, Central PUBLISHED, same source and complete receipt | Authenticated `external-state.json`, `release-run.json`, candidate/ledger, tag source and exact Release body, public Central file hashes. |
| 16 | Fresh Central Maven consumption, Java-only model loading and offline replay | Three `central-java-*` receipts: independent empty caches, Maven Central transfer/origin records, loaded-JAR hash, real public API checks, Java-only online/offline logs. |

## Reproduce and audit

Use the [release guide](releasing.md#consume-the-published-central-version).
`commands.txt` in the archive records preparation, the single publish dispatch and
all three formal consumer commands. Public source and ledger provide everything
needed to rerun consumer acceptance without access to publishing credentials.
Create a separate checkout of the release source and new empty work directories;
the signature verifier intentionally rejects a later evidence-only source commit.

```sh
mkdir -p /tmp/magika-issue10-receipt
# From this repository:
tar -xzf docs/releases/issue-10-verification.tar.gz -C /tmp/magika-issue10-receipt
cd /tmp/magika-issue10-receipt/issue-10
sha256sum -c SHA256SUMS
```

`build-evidence.py` records the independent extraction/assertion logic for XML,
consumer, artifact and external-state evidence. It used Python only for offline
evidence preparation; the product, regular Maven tests, release tooling and Java
consumers did not require Python.

## Review and workspace receipt

The actual staged, unstaged and new ticket files were reviewed before each
necessary commit against fixed BASE. The empty pre-preparation BASE...HEAD diff
was not used as a substitute for reviewing uncommitted work. Two independent
code-review axes examined preparation and final evidence. The initial preparation
Standards review raised one optional duplicated-launch smell; one shared JVM
launch resolved it. Preparation Spec review had no findings.

## Standards

Reviewed `git diff 65cb8714f42be2632548c2a28513371b070baf5a...HEAD` (preparation commit `c409ddf`) and all five staged evidence files, including the archive's extracted contents. The unstaged diff was empty. All 24 original untracked paths and their SHA-256 values still matched the pre-task inventory; none was staged.

**Documented-standard breaches: none found.** Changes preserve `CONTEXT.md` terminology and independent-maintainer identity; ADR 0001's reference-compatibility and Python-free runtime boundary; ADR 0002's Java 8/Ubuntu x64/CPU/ORT 1.30.0 scope; ADR 0004's bundled-asset/offline contract; and parent #1's public-API/real-ORT verification boundary. No production code, dependencies, workflow gates, test assertions or resource/numerical limits changed since BASE.

The staged claims match the inspected evidence: original XML contains 39 unit and 17 integration cases per JVM with no failures/errors/skips; all three Central consumers retain direct POM/JAR transfer receipts, successful online/offline builds, actual loaded-JAR hashes and both Java-only runtime logs. All eight downloaded artifacts/signatures match the candidate. The measured and released JARs have identical 27 classes and runtime resources; only the embedded POM differs. Documentation correctly preserves #8's original measurement environment and the unresolved historical #6 descriptor assertion.

Recovery reporting distinguishes the failed first observer from the successful second publish-job attempt. Captured observations preserve the same deployment ID/name through PUBLISHING/PUBLISHED despite empty PURLs; the final published flag, signed candidate/public bytes and ledger identity agree. No recovery check was weakened in the diff. Release source and later evidence are explicitly separated.

The staged archive matches the inspected extracted files, uses only ordinary files/directories within its root, and includes a checksum entry for every other file. The review-results paragraph is completed by this final aggregation.

**Heuristic findings: none remaining.** The preparation review's optional duplicated-launch smell is resolved by the shared JVM invocation. No other material Fowler-baseline smell was found; immutable evidence copies are purposeful provenance, not duplicated executable responsibility. Tooling-enforced concerns were excluded. No repository edits or heavy tests were performed.

## Spec

No Spec findings: no missing/partial requirement, scope creep, or incorrectly implemented requirement found in the release preparation and final evidence changes.

Reviewed `BASE...HEAD` from fixed BASE `65cb8714f42be2632548c2a28513371b070baf5a` to release source `c409ddf8fc8c3d53ce65ed73c2b06a18845f7366`, all five staged evidence files, the empty unstaged diff, and the complete 24-file protected untracked inventory. The source and subsequent evidence commit are explicitly distinguished.

The seven #10 criteria and all 16 parent acceptance mappings have inspectable supporting evidence. I independently parsed every archived JVM XML suite: each actual Java 8/17/21 runtime has 39 unit and 17 integration cases, with no failures, errors or skips. Inspected test assertions and probe output support the reported 9,261/141/207 references, exact fields, unchanged 1e-5 bound, input/batch/concurrency contracts, constrained-heap workloads and resource observations.

The archived publish attempts, preflight and recovery state preserve the same source, signed candidate and deployment ID. Attempt 1's missing-PURL interruption is disclosed; authenticated PUBLISHED evidence, coordinate-specific published status and all eight matching public files support the existing recovery path. Final tag/Release identity and the complete deterministic Release body match the source.

I compared all eight archived Central artifact/signature bytes with the candidate and bundle, inspected official distributions against the checkout, checked all 162 archived file digests and archive/extracted equality, and confirmed the machine receipt matches its archived summary. All three consumer records include genuine Central transfers/cache origins, matching actual loaded-JAR hashes, Java-only execution and a successful network-isolated repeat. Archived example sources match the release source.

Independent JAR comparison confirms all 27 classes and runtime resources match the authenticated #8 measurement; only the embedded POM differs. The report retains original measurement limits and the unresolved historical #6 descriptor observation without claiming a fix.

No heavy test rerun was performed.

Review totals: **Standards 0; Spec 0**. Neither axis has an unresolved finding.

The 24 original untracked files retained their original contents and untracked
state. They were never staged or committed. Publication source and later evidence
were committed/pushed separately, with no PR, merge or issue write. The final
conversation receipt supplies the evidence commit SHA without confusing it with
the immutable release source above.
