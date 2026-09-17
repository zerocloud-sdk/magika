# Magika Java SDK

An independently maintained Java 8 SDK by ZeroCloud SDK. It identifies complete
byte arrays, saved regular files, streams and lazy batches of paths offline using Google's bundled `standard_v3_3` Magika model and the
official ONNX Runtime CPU dependency, version `1.30.0`.

This implements [issue #2](https://github.com/zerocloud-sdk/magika/issues/2),
[issue #3](https://github.com/zerocloud-sdk/magika/issues/3),
[issue #4](https://github.com/zerocloud-sdk/magika/issues/4),
[issue #5](https://github.com/zerocloud-sdk/magika/issues/5),
[issue #6](https://github.com/zerocloud-sdk/magika/issues/6) and
[issue #7](https://github.com/zerocloud-sdk/magika/issues/7): concurrent byte array, file, stream and batch
identification with all three official prediction modes, defaulting to
**HIGH_CONFIDENCE**. Release publication is a separate ticket.
Version `0.1.0` here is a local build, not a claim
that a release has been published to Maven Central.

## Build and use

Build with JDK 21 and Maven 3.8.7 or later. Compilation uses `--release 8`, including
the Java 8 standard API check. On the verified Ubuntu installation:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -B -ntp clean install
```

After installation, a consumer can depend on:

```xml
<dependency>
  <groupId>net.zerocloud</groupId>
  <artifactId>magika</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
import java.nio.charset.StandardCharsets;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;

byte[] content = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<<>>\n%%EOF\n"
        .getBytes(StandardCharsets.US_ASCII);
DetectionResult result;
try (Magika magika = Magika.create()) {
    result = magika.identify(content);
    System.out.println(magika.getModelInfo().getSdkVersion());
    System.out.println(magika.getModelInfo().getModelVersion());
}
System.out.println(result.getLabel());    // pdf; usable after close
System.out.println(result.getMimeType()); // application/pdf
```

`Magika.builder().intraOpThreads(2).build()` configures a positive ORT
intra-operation thread count. The default is 1; graph execution is always
sequential. `create()` and `build()` validate all three pinned asset digests,
configuration, ordered labels, metadata, and native model signature, and create a
real session before returning. They do not download anything.
Like the fixed upstream implementation, creation disables telemetry on the
JVM-shared ORT environment; this setting also affects other users of that environment.

Reuse one instance across requests and threads. All `identify` overloads and
`identifyAll` share its real ORT Session, with separate sampling, tensors and
results for each call. The application owns request scheduling; the SDK creates
no worker pool. Builders are not thread-safe. Keep each input stable and
coordinate any input or callback state shared by the application.

## Shared lifetime and shutdown

The lifecycle is **OPEN → CLOSING → CLOSED**. `close()` stops admission before
waiting. New identification calls throw `IllegalStateException` without reading
their input or consuming their iterator. An accepted call keeps running until it
returns or fails under its normal contract. An accepted batch includes **all
remaining inputs and callbacks**, not just its current inference batch.

Once all calls exit, close releases the Session, then SessionOptions, exactly
once. It leaves the JVM-shared OrtEnvironment available to other instances.
Repeated and concurrent closes wait for the same release to finish. If a closer
is interrupted while waiting, it still finishes waiting and restores its interrupt
flag before exit. Release failures are reported, other owned resources still get
a cleanup attempt, and the instance stays closed. Later closes report the same
underlying failure without retrying release. Immutable results and
`getModelInfo()` remain usable after close.

Close has no timeout and does not forcibly terminate blocking input, a user
callback, or native inference. Configure timeouts at the input source and arrange
for callbacks to finish. **Same-thread close from an active call** (including
stream/file reads, iterator code and callbacks) immediately throws
`IllegalStateException` without changing the lifecycle. Callbacks execute without
the lifecycle lock, but must not wait for another thread to close this instance:
close is waiting for the callback, forming a caller-created waiting cycle.

The [runnable shared-instance example](examples/offline/src/main/java/example/SharedInstanceExample.java)
creates one instance, serves 16 requests on four application workers, then shuts
down and reads a retained result. In a service, create the instance at startup,
stop accepting application requests at shutdown, finish queued requests, and close
the instance. A task queued on an application executor has **not** yet been
accepted by the SDK; admission occurs when it enters an identification method.
The standalone and isolated consumers below also run this example.

## Saved uploads and regular files

Save the complete upload and finish writing before passing its `Path`:

```java
import java.nio.file.Path;
import java.nio.file.Paths;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;

Path savedUpload = Paths.get("uploads", "completed-upload.bin");
try (Magika magika = Magika.create()) {
    DetectionResult result = magika.identify(savedUpload);
    System.out.println(result.getLabel() + " " + result.getMimeType());
}
```

The [runnable saved-upload example](examples/offline/src/main/java/example/SavedUploadExample.java)
copies an application-owned upload stream into a temporary file, closes the stream,
identifies the saved file and deletes it. It compiles against the installed SDK and
also runs in the isolated consumer below.

`identify(Path)` follows symbolic links and accepts only regular files. Directories,
devices and pipes are rejected before opening content; broken links, missing files
and unreadable files fail with `MagikaException.Category.INPUT`. The exception
includes the supplied path in `getContext()` and preserves the available original
cause in `getCause()`. Input failures throw; they never return `unknown` or retry.
A null `Path` throws `IllegalArgumentException`.

The SDK opens one read handle, seeks to the head and tail, and closes its handle
before inference, including on sampling failures. Each raw window is at most
4096 bytes. There is no default file size limit, and sampling buffers do not grow
with file length. This bound excludes model/ORT memory and application buffers.
The application owns the file and must keep both its content and path stable
throughout identification, including symbolic-link targets. The SDK provides no
snapshot and cannot detect every concurrent modification. Observable truncation,
read failures or size changes during sampling report input errors without retrying.
Same-content files and complete byte arrays use the same rules in all three modes.

## Upload streams

Pass a caller-owned `InputStream` directly to `identify`. Identification consumes
all remaining bytes **from the current position to EOF**, using the same rules
and returning the same result fields as a complete `byte[]` in all three modes.

```java
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;

// An HTTP request body can also be supplied directly as upload.
try (InputStream upload = Files.newInputStream(Paths.get("uploads", "completed-upload.bin"));
     Magika magika = Magika.builder().maxStreamBytes(128L * 1024 * 1024).build()) {
    DetectionResult result = magika.identify(upload);
    System.out.println(result.getLabel() + " " + result.getMimeType());
} // The application closes its stream here.
```

The default stream limit is **67,108,864 bytes (64 MiB)**. Configure a nonnegative
`long` with `maxStreamBytes`; negative values throw `IllegalArgumentException`,
and zero accepts only an empty remaining stream. `Long.MAX_VALUE` is supported.
Each built instance keeps its limit even if the builder is later changed.
The limit applies only to streams, independently of byte array and Path inputs.

At most **one extra byte** is consumed to distinguish a stream exactly at the
limit from one that exceeds it. An oversized stream immediately throws
`MagikaException` with category `INPUT` and `maxStreamBytes` in its context;
the SDK stops reading and never identifies a truncated prefix. Read failures
also throw `INPUT` and preserve the original cause. Empty content and unknown
types remain successful results. A null stream throws `IllegalArgumentException`;
a closed SDK rejects stream identification before any read.

The SDK **never closes or resets** the caller's stream, including on failure.
The caller arranges any later replay, saving or reopening. Configure blocking
read timeouts on the input source (for example, the HTTP connection); the SDK
does not impose a timeout. Sampling uses head and tail windows of at most
4096 bytes each, one fixed 4096-byte work buffer and a `long` total length.
It never saves the complete stream in memory or a temporary file. This sampling
memory guarantee excludes the model, ORT, caller-owned buffers and retained results.

The [runnable stream-upload example](examples/offline/src/main/java/example/StreamUploadExample.java)
consumes an application envelope first, identifies the remaining upload, and
closes the stream in the application's resource block. The isolated consumer
below compiles and runs it against the installed SDK.

## Ordered lazy batches

`identifyAll(Iterator<Path>, Consumer<BatchItemResult>)` runs synchronously on the
calling thread and returns `BatchSummary`. For example, the application can own
a lazy directory traversal and its stream lifetime:

```java
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import net.zerocloud.magika.BatchIdentificationException;
import net.zerocloud.magika.BatchSummary;
import net.zerocloud.magika.Magika;

try (Stream<Path> paths = Files.walk(Paths.get("uploads"));
     Magika magika = Magika.builder().batchSize(32).build()) {
    try {
        BatchSummary summary = magika.identifyAll(paths.iterator(), item -> {
            if (item.isSuccess()) {
                System.out.println(item.getInputIndex() + ": " + item.getResult().get().getLabel());
            } else {
                System.err.println(item.getPath() + ": " + item.getError().get().getMessage());
            }
        });
        System.out.println("Delivered " + summary.getDeliveredCount() + ", successful "
                + summary.getSuccessCount() + ", file failures " + summary.getFailureCount());
    } catch (BatchIdentificationException error) {
        System.err.println("Aborted at " + error.getStage() + ", delivered "
                + error.getDeliveredCount() + ", input index " + error.getInputIndex());
        // Reconcile callback side effects and consumed input before choosing recovery.
    }
}
```

The [runnable batch example](examples/offline/src/main/java/example/BatchExample.java)
demonstrates successes, a missing file, duplicate paths and a throwing callback
with exact delivery progress. It also runs in the independent offline consumer.
Directories produced by the traversal above are delivered as per-file failures;
the application may instead filter them when planning its input sequence.

- `batchSize` defaults to **32**, including both `create()` and a default builder.
  It must be positive and at most **262143**, so INT32 tensor element counts and
  direct-buffer byte capacities fit an `int`. Excessive settings fail with
  `IllegalArgumentException`; a large valid setting allocates only for actual inputs.
  Each built instance keeps its configuration after later builder changes.
- Each batch consumes at most that many input positions, packs only model inputs
  into one real ORT tensor, then delivers all outcomes in original order. Repeated
  paths retain separate zero-based `long` indices and the original `Path` objects.
  Exactly one of `getResult()` and `getError()` is present. Empty and unknown
  detections count as successes; unreadable/non-regular files and read/close errors
  have category `INPUT` and processing continues.
- No input/result history or SDK worker pool is created. Slow callbacks prevent
  the next batch from being consumed. SDK working storage grows with the current
  batch, excluding model/ORT allocations and values retained by the application.
- Null iterator/consumer arguments are illegal arguments. A null iterator element,
  iterator failure, system/inference failure or throwing callback aborts the call
  as `BatchIdentificationException` (category `BATCH`), with the cause chain intact.
  `getStage()` distinguishes `ITERATION`, `IDENTIFICATION`, `CALLBACK` and
  `INTERRUPTED`. `getInputIndex()` is absent for a failed `hasNext` or a model failure
  involving several rows; it identifies `next`, null-element, sampling or callback
  failures, and model failures with exactly one model row.
- Only a callback that returns normally increases `getDeliveredCount()`. The
  delivered prefix remains valid, each position is called at most once, and no
  later callback is attempted after abort. The iterator may already have advanced
  beyond the delivered prefix. A throwing callback may have side effects of its
  own: the SDK performs no retry or rollback. The application owns traversal,
  scheduling, persistence and recovery, including closing iterator resources.
- Interrupts are observed between inputs, before inference/delivery and after a
  batch, with the interrupt flag preserved and progress recorded. This cannot
  immediately stop blocking input, user code or an entered native operation.
  Callbacks execute without a lifecycle lock and may identify other inputs while
  the instance is open. Closing rejects nested new calls too. Callbacks must not
  close the same instance or wait for another thread to close it.

The pinned upstream asset bytes and their published digests remain unchanged.
After authentication the runtime applies an equivalent layout transformation to
two LayerNorm reductions, and disables graph optimization that would undo it.
This avoids ORT CPU accumulation-order differences between single and multirow
inputs, while keeping genuine multirow inference and the `1e-5` score tolerance.
Weights, labels and prediction rules are unchanged. The digests returned by
`getModelInfo()` identify the bundled upstream assets. See the
[numerical diagnosis and acceptance evidence](docs/verification-issue-6.md).

## Prediction modes

Select a mode with `Magika.builder().predictionMode(PredictionMode.MEDIUM_CONFIDENCE).build()`.
`Magika.create()` and a builder without an explicit mode use `HIGH_CONFIDENCE`.
Builders are mutable and not thread-safe; each built instance keeps its own
configuration even if the builder is later changed or reused. A null mode throws
`IllegalArgumentException`.

All modes first apply the fixed configuration's type mapping, such as
`randomtxt` to `txt` and `randombytes` to `unknown`.

| Mode | When the mapped prediction is kept |
| --- | --- |
| `HIGH_CONFIDENCE` | Score is at least the raw label's configured threshold; labels without one use `medium_confidence_threshold` |
| `MEDIUM_CONFIDENCE` | Score is at least the uniform `medium_confidence_threshold`, currently 0.5 |
| `BEST_GUESS` | Always, including low scores; type mapping still applies |

HIGH/MEDIUM scores strictly below the threshold fall back to `txt` or `unknown`,
according to the **mapped** type's text metadata. Equality keeps the mapped label.
The thresholds come from the bundled configuration and cannot be customized.

```java
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;
import net.zerocloud.magika.PredictionMode;

byte[] ambiguousContent = {0, 1, 2, 3, 4, 5, 6, 7};
for (PredictionMode mode : PredictionMode.values()) {
    try (Magika magika = Magika.builder().predictionMode(mode).build()) {
        DetectionResult result = magika.identify(ambiguousContent);
        System.out.println(mode + ": raw=" + result.getRawPrediction().get().getLabel()
                + ", final=" + result.getLabel() + ", score=" + result.getScore()
                + ", reason=" + result.getOverwriteReason());
    }
}
```

This example executes the model: the raw label is `wasm` with score about 0.3142.
HIGH/MEDIUM return `unknown` with `LOW_CONFIDENCE`; BEST_GUESS returns `wasm`
with `NONE`. All three retain the same raw score. The
[standalone consumer](examples/offline/src/main/java/example/OfflineExample.java)
compiles and runs this mode comparison alongside the default PDF example.

## Input and results

`byte[]` represents **complete content**, not a header prefix. Keep it unchanged
until `identify` returns. The SDK neither modifies nor retains the array, and
does not copy the entire input. It extracts head and tail features using raw
windows of at most 4096 bytes each, 1024 tokens per end, and padding token 256.

`DetectionResult`, `RawPrediction`, `ModelInfo`, `BatchItemResult` and `BatchSummary`
are immutable Java values.
Results never own native handles or need closing.

| Result accessor | Meaning |
| --- | --- |
| `getLabel()` | Final type label, including successful `empty` and `unknown` results |
| `getMimeType()` | Final label's MIME string from the fixed upstream metadata |
| `getScore()` | Original top-1 model score when the model ran; otherwise the rule score 1.0 |
| `getRawPrediction()` | `Optional<RawPrediction>` with raw label and score, empty if no inference ran |
| `getOverwriteReason()` | `NONE`, `OVERWRITE_MAP`, or `LOW_CONFIDENCE` |
| `isModelUsed()` | Whether this call actually ran inference |
| `getModelVersion()` | `standard_v3_3`, independently of the SDK version |

A mapped or low-confidence result retains the original top-1 score. **It is not
the probability of the final MIME type.** Empty content and short-content rules
have no raw prediction, `isModelUsed() == false`, score 1.0 and reason `NONE`.
The reference data's `dl=undefined` corresponds to an empty Optional.

`OVERWRITE_MAP` means the configured mapping changed the label. `LOW_CONFIDENCE`
means low-score rejection changed it; this reason takes precedence when both
mapping and rejection apply. If the final label equals the raw label, the reason
is `NONE`, even at a low score (for example, raw `txt` falling back to `txt`).
BEST_GUESS never returns `LOW_CONFIDENCE`. Empty and short-content rules have the
same result contract in all three modes.

The fixed upstream rules use strict UTF-8 decoding for short content; invalid
UTF-8 returns `unknown`. Long leading whitespace can also select the short-content
rule using only the first 4096-byte window. Leading head whitespace and trailing
tail whitespace use exactly Python's six ASCII byte whitespace values. Metadata
strings are preserved verbatim, including upstream's `text-ocaml` for OCaml.

| Failure | Public exception |
| --- | --- |
| Null input/callback/mode, invalid batch size, nonpositive thread count or negative stream limit | `IllegalArgumentException` |
| Identification during/after close; same-thread close inside an accepted call | `IllegalStateException` |
| Missing, corrupt or inconsistent assets | `MagikaException`, category `ASSET_VALIDATION` |
| Native loading, Session initialization or signature failure | `MagikaException`, category `MODEL_INITIALIZATION` |
| Non-regular, missing or unreadable file; sampling or file-handle close failure | `MagikaException`, category `INPUT` |
| Stream read failure or configured stream limit exceeded | `MagikaException`, category `INPUT` |
| Inference/output failure | `MagikaException`, category `INFERENCE` |
| Instance resource-release failure | `MagikaException`, category `RESOURCE_RELEASE` |
| Aborted batch (iterator, system, callback or interruption) | `BatchIdentificationException`, category `BATCH`, with stage/progress |

`MagikaException` is unchecked and exposes `getCategory()`, `getContext()` and the
original `getCause()` when one exists. Validation mismatches without an underlying
exception have no cause. Cleanup failures are preserved as suppressed exceptions.
An initialization failure never returns a partial instance. A release failure
leaves the instance closed. Operational failures are never disguised as `unknown`.
Callers do not need to handle checked `OrtException`.

## Offline consumer and native runtime

The SDK main JAR includes the model, configuration, type metadata, asset manifest,
license and notices. ORT and Gson remain separate, unmodified Maven dependencies;
Gson also brings Error Prone annotations. Install those dependencies once, then
run without network access or Python. There is no runtime asset download.

The [standalone consumer](examples/offline) has no parent POM or dependency on
the SDK source tree. After the SDK installation above:

```sh
mvn -B -ntp -f examples/offline/pom.xml clean package
/usr/lib/jvm/java-8-openjdk-amd64/bin/java \
  -cp 'examples/offline/target/offline-byte-array-1.0.0.jar:examples/offline/target/dependency/*' \
  example.OfflineExample
bash scripts/verify-offline.sh /usr/lib/jvm/java-8-openjdk-amd64
```

The last command requires Ubuntu's `sudo`, `unshare`, `chroot`, `rsync`, `unzip`
and `ldd`, plus `ip` from iproute2. It creates a temporary Java-only root containing a JVM, required
native libraries, packaged consumer and runtime dependencies; it starts a fresh
network namespace with no external interface. Python and Maven are absent.
Only this verification harness needs those Linux tools or privilege; the SDK
needs neither. The script removes its temporary root after execution.

The default ORT loader extracts native libraries to a writable temporary directory
and loads them through JNI. That location must permit native loading. The ORT
property `onnxruntime.native.path` can instead point to an existing compatible
native-library directory; it is not an extraction-directory setting. Native
loading errors retain their cause in `MagikaException`.

## Verification and provenance

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
native ORT version. [CI](.github/workflows/verify.yml) uses Ubuntu 24.04 x64, JDK 21
for compilation, separate Java 8/17/21 test JVMs, and the isolated packaged consumer.
The [stream verification record](docs/verification-issue-5.md),
[regular-file verification record](docs/verification-issue-4.md),
[prediction-mode verification record](docs/verification-issue-3.md) and
[initial SDK verification record](docs/verification-issue-2.md) record executed checks and
limits. Platform evidence is limited to Ubuntu 24.04 x64 / CPU; other distributions,
architectures, libc versions and GPU execution are not verified.

The [asset manifest](src/main/resources/net/zerocloud/magika/model/asset-manifest.json)
records the fixed upstream commit, model contract, exact byte sizes, sources,
SHA-256 digests, license, and both original feature/content reference datasets.
The separate [path manifest](src/test/resources/reference/path-manifest.json)
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
release failures. See the [concurrency verification record](docs/verification-issue-7.md).

The [standalone measurement tool](benchmarks/README.md) runs complete public SDK
Path, InputStream, lazy batch and shared-instance workloads. The
[versioned performance and resource report](docs/performance/issue-8-v1.md)
includes measured throughput, separately defined latency metrics, Java heap and
process observations, raw evidence and reproduction commands. Its figures apply
to the recorded corpus and environment; they are not a fixed performance SLA.

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
Own code and Google Magika adaptations/assets are under [Apache-2.0](LICENSE);
see [NOTICE](NOTICE) for upstream attribution. This SDK is not a Google product.
