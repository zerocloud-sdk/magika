# Development and verification

[English user guide](../README.md) · [中文使用文档](../README.zh-CN.md)

This guide collects source-build instructions, verification evidence and model
provenance for maintainers. For installation and application integration, start
with the user guide. Run the shell commands below from the repository root.

- [Build and verify](#build-and-verify)
- [Model and reference provenance](#model-and-reference-provenance)
- [Model upgrades and version policy](#model-upgrades-and-version-policy)
- [Sampling and numerical behavior](#sampling-and-numerical-behavior)
- [Isolated offline verification](#isolated-offline-verification)

## Build and verify

Build from source with JDK 21 and Maven 3.8.7 or later. Compilation uses
`--release 8`, including the Java 8 standard API check. `mvn -B -ntp clean install`
builds, tests and installs the local SDK. For byte-identical release artifacts,
use the pinned Temurin 21.0.12.1+1 compiler described in the release guide.

`mvn verify` is the acceptance entry point: Java tests read the original static
gzip fixtures, exercise real ORT, validate packaged resources and bytecode, reject
altered assets, and run separate JVM probes for bounded input copying, native
thread configuration, sequential native resource reuse, a file and a generated stream
larger than the heap and the `int` range, input errors and owned file handles.
Stream and special-file probes have a process timeout;
permission checks require a real denied open by an unprivileged test user.
Javadoc generation
also runs and rejects documentation warnings. No Python generation step is used.

To build with JDK 21 and really execute all tests and native inference on each
installed Java version:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -B -ntp clean
for runtime in 8 17 21; do
  mvn -B -ntp -Dtest.java.home="/usr/lib/jvm/java-$runtime-openjdk-amd64" verify
  mkdir -p "target/verification/java-$runtime"
  cp -R target/surefire-reports target/failsafe-reports "target/verification/java-$runtime/"
done
```

The test logs print the actual runtime version, vendor, home, OS/architecture and
native ORT version. [CI](../.github/workflows/verify.yml) uses Ubuntu 24.04 x64, JDK 21
for compilation, separate Java 8/17/21 test JVMs, and the isolated packaged consumer.
The [stream verification record](verification-issue-5.md),
[regular-file verification record](verification-issue-4.md),
[prediction-mode verification record](verification-issue-3.md) and
[initial SDK verification record](verification-issue-2.md) record executed checks and
limits. Platform evidence is limited to Ubuntu 24.04 x64 / CPU; other distributions,
architectures, libc versions and GPU execution are not verified.

## Model and reference provenance

The [asset manifest](../src/main/resources/net/zerocloud/magika/model/asset-manifest.json)
records the fixed upstream commit, model contract, exact byte sizes, sources,
SHA-256 digests, license, and both original feature/content reference datasets.
The separate [path manifest](../src/test/resources/reference/path-manifest.json)
pins the path reference and all 69 original files to the same upstream commit,
with per-file sources, sizes and SHA-256 digests. All references and original
input files are test-only and excluded from the production JAR. The 9,261
feature cases use head 128, tail 64 and window 512; separate boundary tests cover
the actual model's 1024/1024 and 4096 configuration. All 141 content cases are
checked through the public API, exactly 47 per mode, with exact raw/final labels,
overwrite reasons and MIME strings, and absolute score error at most `1e-5`.
All 207 path cases are checked through public `identify(Path)`, exactly 69 per mode,
against both the fixed reference and complete `byte[]` results. Boundary cases
also compare their exact features through the restricted feature contract.
All 141 content references and the contents of all 207 path references also run
through public `identify(InputStream)`, with exact result equality to the other
entry points and the same official score tolerance. Stream boundaries cover
different chunk sizes, short reads, strict UTF-8 and long whitespace in all modes.
Neither these cases nor the deterministic rule tests
establish accuracy for all 214 model classes.

The [supported final-label list](supported-types.md) is derived from the
authenticated model vocabulary, its type mapping, short-content rules and
confidence fallbacks. It contains 214 possible final labels; the 353-entry
knowledge base is not the support list. `randombytes` and `randomtxt` appear only
as raw predictions, mapped to `unknown` and `txt` in every prediction mode.

All 207 path references also run through `identifyAll` in the three modes, with
exact labels/MIME/reasons and the same `1e-5` score tolerance. Mixed boundaries,
rules, failures and duplicates are compared to single Path calls at batch sizes
1, 7 and 32. Existing single-entry comparisons remain exact. Batch probes observe
real ORT tensor shapes, native failures and resource closure through ORT's public
boundary, and process 1,000,003 lazy input positions in a separate 64 MiB JVM.
The ORT probe agent and ASM are test-only; they are absent from SDK runtime dependencies.

Shared-instance tests compare every result field across four concurrent entry
points in all modes, preserving exact single-entry comparisons and the batch
score tolerance. Controlled reads, iterators, callbacks and public ORT boundaries
verify full-call draining, untouched rejection, reentrancy, interrupted and
concurrent closers, per-call tensor/result ownership, initialization cleanup and
release failures. See the [concurrency verification record](verification-issue-7.md).

The [standalone measurement tool](../benchmarks/README.md) runs complete public SDK
Path, InputStream, lazy batch and shared-instance workloads. The
[versioned performance and resource report](performance/issue-8-v1.md)
includes measured throughput, separately defined latency metrics, Java heap and
process observations, raw evidence and reproduction commands. Its figures apply
to the recorded corpus and environment; they are not a fixed performance SLA.

## Model upgrades and version policy

Before upgrading the model or its companion configuration/metadata, pin the new
asset set and upstream reference sources together, then rerun compatibility
acceptance:

- All official feature references (currently 9,261), plus actual model window,
  padding, whitespace and strict UTF-8 boundaries.
- All content references in each of the three modes (currently 141, 47 per mode),
  checking raw/final labels, reasons, MIME and the `1e-5` score tolerance.
- All 207 path references and 69 authenticated original files, plus Path/byte[]
  feature and result equality, file errors, single-handle ownership and bounded sampling.
- Stream equivalence for those content/path references and boundary cases, current
  position, caller ownership, read failures, size limits and the heap-limited generated stream.
- Batch reference parity, lazy ordered delivery, file errors, abort/interruption
  progress, native tensor/resource checks and the million-path limited-heap probe.
- Shared Session concurrent inference, full-call close draining, reentrant close,
  interrupted/concurrent closers, release failure convergence and resource isolation.
- HIGH/MEDIUM below/equal/above threshold cases, per-label threshold defaults,
  mapping, text/binary fallback, unchanged-label reasons and BEST_GUESS low scores.
- Rule short circuits for empty/short content in every mode: absent raw
  prediction, score 1.0, no model use and reason `NONE`; original scores after rewrites.
- The real Java 8/17/21 CPU/ORT `mvn verify` matrix on Ubuntu 24.04 x64, including
  asset/signature validation, packaged artifacts, strict Javadoc and the standalone
  consumer with no network or Python. Compile with JDK 21 targeting Java 8.

`getModelInfo()` separately reports SDK version, model version, upstream commit and
an unmodifiable filename-to-SHA-256 map. Model or rule changes require compatibility
verification and a new SDK version; the model is not a separate Maven artifact.
Version policy: fixes increment the patch; new capabilities, model/rule updates,
and breaking 0.x APIs increment at least the minor version, with migration notes
for breaking changes. Published coordinates must never be replaced.

Public Javadoc is generated in `target/reports/apidocs/` and the Javadoc JAR.
Own code and Google Magika adaptations/assets are under [Apache-2.0](../LICENSE);
see [NOTICE](../NOTICE) for upstream attribution. This SDK is not a Google product.

## Sampling and numerical behavior

Input sampling uses raw head and tail windows of at most 4096 bytes each,
1024 tokens per end, and padding token 256. Stream sampling adds a fixed
4096-byte work buffer and a `long` total length; it does not keep the complete
stream in memory or a temporary file. These bounds exclude model/ORT memory,
application buffers and retained results.

The pinned upstream asset bytes and their published digests remain unchanged.
After authentication the runtime applies an equivalent layout transformation to
two LayerNorm reductions, and disables graph optimization that would undo it.
This avoids ORT CPU accumulation-order differences between single and multirow
inputs, while keeping genuine multirow inference and the `1e-5` score tolerance.
Weights, labels and prediction rules are unchanged. The digests returned by
`getModelInfo()` identify the bundled upstream assets. See the
[numerical diagnosis and acceptance evidence](verification-issue-6.md).

The fixed upstream rules use strict UTF-8 decoding for short content; invalid
UTF-8 returns `unknown`. Long leading whitespace can also select the short-content
rule using only the first 4096-byte window. Leading head whitespace and trailing
tail whitespace use exactly Python's six ASCII byte whitespace values. Metadata
strings are preserved verbatim, including upstream's `text-ocaml` for OCaml.

## Isolated offline verification

Build and install the SDK, then package the independent consumer before running
the isolation harness. JDK 21 is required to build the SDK; the last command
selects Java 8 for the isolated runtime.

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -B -ntp clean install
mvn -B -ntp -f examples/offline/pom.xml clean package
bash scripts/verify-offline.sh /usr/lib/jvm/java-8-openjdk-amd64
```

The isolation harness requires Ubuntu's `sudo`, `unshare`, `chroot`, `rsync`, `unzip`
and `ldd`, plus `ip` from iproute2. It creates a temporary Java-only root containing a JVM, required
native libraries, packaged consumer and runtime dependencies; it starts a fresh
network namespace with no external interface. Python and Maven are absent.
Only this verification harness needs those Linux tools or privilege; the SDK
needs neither. The script removes its temporary root after execution.
