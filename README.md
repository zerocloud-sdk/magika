# Magika Java SDK

**English** | [简体中文](README.zh-CN.md)

Identify file types from their content in Java. Independently maintained by
ZeroCloud SDK, this library bundles Google's `standard_v3_3` Magika model for
offline identification. It supports complete byte arrays, regular files, streams
and ordered batches of paths, with reusable instances for concurrent requests.

[Maven Central](https://central.sonatype.com/artifact/net.zerocloud/magika/0.1.0) ·
[Release v0.1.0](https://github.com/zerocloud-sdk/magika/releases/tag/v0.1.0) ·
[Supported types](docs/supported-types.md) · [Examples](examples/offline)

## Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Choose an input](#choose-an-input)
- [Configuration](#configuration)
- [Prediction modes](#prediction-modes)
- [Understand results](#understand-results)
- [Handle errors](#handle-errors)
- [Reuse instances and shut down](#reuse-instances-and-shut-down)
- [Offline deployment and troubleshooting](#offline-deployment-and-troubleshooting)
- [Run the examples](#run-the-examples)
- [Versions and further reading](#versions-and-further-reading)
- [License](#license)

## Requirements

| Item | Requirement |
| --- | --- |
| Application runtime | Java 8 compatible; verified on Java 8, 17 and 21 |
| Verified platform | Ubuntu 24.04 x64, CPU, with ONNX Runtime 1.30.0 |
| Runtime dependencies | Resolved transitively by Maven or Gradle |
| Network | Needed to obtain dependencies; identification runs offline afterward |
| Build this SDK from source | JDK 21 and Maven 3.8.7 or later; see the [development guide](docs/development.md) |

Using the published dependency does not require building the SDK or installing
Python. Other operating systems, distributions, architectures, libc versions and
GPU execution have not been verified by this project.

## Installation

### Maven

Add this inside your project's `<dependencies>` section. The artifact is
published to Maven Central; no extra repository is needed.

```xml
<dependency>
  <groupId>net.zerocloud</groupId>
  <artifactId>magika</artifactId>
  <version>0.1.0</version>
</dependency>
```

### Gradle

For `build.gradle` (Groovy DSL), in a project with the Java plugin:

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'net.zerocloud:magika:0.1.0'
}
```

For `build.gradle.kts` (Kotlin DSL):

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("net.zerocloud:magika:0.1.0")
}
```

The SDK JAR contains its model assets. Keep the transitive ONNX Runtime and Gson
dependencies on your runtime classpath; copying only the SDK JAR is insufficient.

## Quick start

After adding the dependency, save this as `QuickStart.java` in your application's
source directory and run `QuickStart.main` from your IDE or build tool:

```java
import java.nio.charset.StandardCharsets;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;

public class QuickStart {
    public static void main(String[] args) {
        byte[] content = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<<>>\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII);
        DetectionResult result;
        try (Magika magika = Magika.create()) {
            result = magika.identify(content);
        }
        System.out.println(result.getLabel());
        System.out.println(result.getMimeType());
    }
}
```

Expected output:

```text
pdf
application/pdf
```

Results are immutable Java values and remain usable after the SDK is closed.
In a service, create one instance at startup and reuse it across requests; see
[lifecycle guidance](#reuse-instances-and-shut-down).

## Choose an input

| Input | API | When to use it |
| --- | --- | --- |
| Complete content in memory | `identify(byte[])` | You already hold the complete file bytes |
| Saved regular file | `identify(Path)` | Saved uploads or large files; reads bounded head/tail windows |
| Caller-owned stream | `identify(InputStream)` | Request bodies or other streams; reads from the current position to EOF |
| Lazy sequence of paths | `identifyAll(Iterator<Path>, Consumer<BatchItemResult>)` | Many files with ordered callbacks and storage bounded by the current batch |

The remaining Java snippets show imports followed by code to place inside a
method. File and stream examples expect `uploads/completed-upload.bin` to exist;
the batch example expects an `uploads` directory. Handle or declare the checked
I/O exceptions from `Files` operations in your application.

### Complete byte arrays

The quick start uses `identify(byte[])`. Supply **complete content**, including
its tail, and keep the array unchanged until the call returns. The SDK does not
modify or retain the array and does not copy the entire input.

### Saved files

Finish writing the upload before identifying it:

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

The SDK follows symbolic links and accepts only regular files. Missing,
unreadable or non-regular inputs throw `MagikaException` with category `INPUT`.
It opens and closes its own file handle, including on failure.

There is no default file-size limit. Sampling uses head/tail windows of at most
4096 bytes each, so sampling memory does not grow with file size. This excludes
model/ORT memory and application buffers. Keep the file, its path and any
symbolic-link target stable: the SDK provides no snapshot and cannot detect every
concurrent modification. Observable read/size changes fail without retrying.

See the [saved-upload example](examples/offline/src/main/java/example/SavedUploadExample.java)
for saving an upload to a temporary file, identifying it and cleaning up.

### Streams

```java
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;

try (InputStream upload = Files.newInputStream(Paths.get("uploads", "completed-upload.bin"));
     Magika magika = Magika.builder().maxStreamBytes(128L * 1024 * 1024).build()) {
    DetectionResult result = magika.identify(upload);
    System.out.println(result.getLabel() + " " + result.getMimeType());
}
```

- Identification consumes all remaining bytes **from the current position to
  EOF**. The same content produces the same result as a complete byte array.
- The SDK **never closes or resets the stream**, even on failure. The
  application's resource block above closes it. Arrange saving or reopening if
  you need to read the content again.
- The default limit is **64 MiB (67,108,864 bytes)**; this example raises it to
  128 MiB. At most one extra byte is read to detect overflow, then an `INPUT`
  error is thrown. The SDK never identifies a truncated prefix.
- Sampling uses two windows of at most 4096 bytes and a 4096-byte work buffer.
  It does not buffer the complete stream or save it to a temporary file. This
  bound excludes model/ORT memory and caller-owned buffers.
- Set blocking-read timeouts at the input source, such as the HTTP connection.
  The SDK has no read timeout.

See the [stream-upload example](examples/offline/src/main/java/example/StreamUploadExample.java)
for identifying content after an application envelope has already been read.

### Ordered batches

`identifyAll` runs synchronously on the calling thread. The application supplies
the iterator, handles each result and owns directory-traversal resources:

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
        BatchSummary summary = magika.identifyAll(
                paths.filter(Files::isRegularFile).iterator(), item -> {
                    if (item.isSuccess()) {
                        System.out.println(item.getInputIndex() + ": "
                                + item.getResult().get().getLabel());
                    } else {
                        System.err.println(item.getPath() + ": "
                                + item.getError().get().getMessage());
                    }
                });
        System.out.println("Delivered " + summary.getDeliveredCount()
                + ", successful " + summary.getSuccessCount()
                + ", file failures " + summary.getFailureCount());
    } catch (BatchIdentificationException error) {
        System.err.println("Aborted at " + error.getStage()
                + ", delivered " + error.getDeliveredCount()
                + ", input index " + error.getInputIndex());
    }
}
```

Results arrive in input order with a zero-based `long` index. Repeated paths are
separate inputs. Exactly one of `item.getResult()` and `item.getError()` is
present; `empty` and `unknown` count as successes. Per-file `INPUT` errors are
delivered to the callback and processing continues. This example filters for
regular files; unfiltered directories would be per-file failures.

The SDK retains only the current batch and creates no worker pool. A slow
callback delays consumption of the next batch. Model/ORT memory and results
retained by the application are additional to batch working storage.

Iterator failures, null elements, system/inference failures, throwing callbacks
and observed interruption abort the call with `BatchIdentificationException`:

| Accessor | Meaning |
| --- | --- |
| `getStage()` | `ITERATION`, `IDENTIFICATION`, `CALLBACK` or `INTERRUPTED` |
| `getDeliveredCount()` | Callbacks that returned normally before the abort |
| `getInputIndex()` | Optional failing input index; absent when no single input can be identified |

Previously delivered results remain valid. The iterator may have advanced beyond
the delivered prefix, and a throwing callback may already have side effects.
Reconcile that state before recovery: the SDK does not retry or roll back.
Interruptions preserve the interrupt flag and are observed between operations;
they cannot forcibly stop blocking reads, callbacks or native inference.

The [batch example](examples/offline/src/main/java/example/BatchExample.java)
demonstrates a missing file, duplicate paths and a callback failure with exact
delivery progress.

## Configuration

`Magika.create()` is equivalent to `Magika.builder().build()`.

| Builder method | Default | Accepted values and effect |
| --- | --- | --- |
| `predictionMode(PredictionMode)` | `HIGH_CONFIDENCE` | One of the three modes below; cannot be null |
| `maxStreamBytes(long)` | `67108864` (64 MiB) | Nonnegative; 0 accepts only empty remaining streams; `Long.MAX_VALUE` is supported; applies only to streams |
| `batchSize(int)` | `32` | 1–262143 input positions per batch |
| `intraOpThreads(int)` | `1` | Positive ORT intra-operation thread count; graph execution stays sequential |

```java
import net.zerocloud.magika.Magika;
import net.zerocloud.magika.PredictionMode;

try (Magika magika = Magika.builder()
        .predictionMode(PredictionMode.HIGH_CONFIDENCE)
        .maxStreamBytes(128L * 1024 * 1024)
        .batchSize(32)
        .intraOpThreads(2)
        .build()) {
    System.out.println(magika.getModelInfo().getSdkVersion());
    System.out.println(magika.getModelInfo().getModelVersion());
}
```

Builders are mutable and not thread-safe. Each built instance keeps its own
configuration even if the builder is later changed. Invalid values throw
`IllegalArgumentException`. Construction validates the bundled assets and
initializes the native session before returning, with no downloads.

## Prediction modes

| Mode | When the mapped model prediction is kept |
| --- | --- |
| `HIGH_CONFIDENCE` (default) | Score meets the raw label's configured threshold; labels without one use the medium threshold |
| `MEDIUM_CONFIDENCE` | Score meets the uniform threshold, currently 0.5 |
| `BEST_GUESS` | Always, including low scores |

All modes apply the bundled type mapping, such as `randomtxt` → `txt` and
`randombytes` → `unknown`. HIGH/MEDIUM scores strictly below the threshold fall
back to `txt` or `unknown` according to the mapped type's text metadata.
Equality keeps the mapped label. Thresholds cannot be customized.
Empty-content and short-content rules behave the same in all modes.

Compare the modes with an ambiguous input:

```java
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;
import net.zerocloud.magika.PredictionMode;

byte[] content = {0, 1, 2, 3, 4, 5, 6, 7};
for (PredictionMode mode : PredictionMode.values()) {
    try (Magika magika = Magika.builder().predictionMode(mode).build()) {
        DetectionResult result = magika.identify(content);
        System.out.println(mode + ": " + result.getLabel()
                + " (" + result.getOverwriteReason() + ")");
    }
}
```

```text
HIGH_CONFIDENCE: unknown (LOW_CONFIDENCE)
MEDIUM_CONFIDENCE: unknown (LOW_CONFIDENCE)
BEST_GUESS: wasm (NONE)
```

All three retain the same raw prediction, `wasm`, with a score of about 0.3142.

## Understand results

| `DetectionResult` accessor | Meaning |
| --- | --- |
| `getLabel()` | Final type label, such as `pdf`, `txt`, `empty` or `unknown` |
| `getMimeType()` | MIME string for the final label, such as `application/pdf` |
| `getScore()` | Original top-1 model score; 1.0 when a rule returned without inference |
| `getRawPrediction()` | `Optional<RawPrediction>` with raw label and score if the model ran |
| `getOverwriteReason()` | `NONE`, `OVERWRITE_MAP` or `LOW_CONFIDENCE` |
| `isModelUsed()` | Whether inference ran |
| `getModelVersion()` | Model version, currently `standard_v3_3` |

**The score is not the probability of the final MIME type.** Mapping and
low-confidence fallback keep the original top-1 score. Empty-content and
short-content rules have score 1.0, no raw prediction, `isModelUsed() == false`
and reason `NONE`. Check the Optional or use `ifPresent` before reading it.

`OVERWRITE_MAP` means type mapping changed the label. `LOW_CONFIDENCE` means
low-score rejection changed it and takes precedence if both apply. If the final
label equals the raw label, the reason is `NONE`. `BEST_GUESS` never reports
`LOW_CONFIDENCE`.

`unknown` means identification completed without a more specific type;
`empty` means zero bytes. Both are successful results. See the
[214 possible final labels](docs/supported-types.md) and their MIME strings.
This vocabulary is not a claim of measured accuracy for every type.

## Handle errors

`MagikaException` is unchecked. It exposes `getCategory()`, `getContext()`
and the original `getCause()` when available; cleanup errors can appear in
`getSuppressed()`. Input or inference failures throw exceptions and never
become an `unknown` detection.

```java
import java.nio.file.Paths;
import net.zerocloud.magika.Magika;
import net.zerocloud.magika.MagikaException;

try (Magika magika = Magika.create()) {
    System.out.println(magika.identify(Paths.get("uploads", "completed-upload.bin")).getLabel());
} catch (MagikaException error) {
    System.err.println(error.getCategory() + ": " + error.getContext());
    error.printStackTrace();
}
```

| Failure | Exception / category |
| --- | --- |
| Null arguments or invalid builder values | `IllegalArgumentException` |
| Identification during/after close, or same-thread close inside an active call | `IllegalStateException` |
| Missing, corrupt or inconsistent assets | `MagikaException` / `ASSET_VALIDATION` |
| Native loading, session initialization or model signature failure | `MagikaException` / `MODEL_INITIALIZATION` |
| File/stream read failure, non-regular file, file-handle close failure or stream limit exceeded | `MagikaException` / `INPUT` |
| Model inference or output failure | `MagikaException` / `INFERENCE` |
| Native resource-release failure | `MagikaException` / `RESOURCE_RELEASE` |
| Aborted batch | `BatchIdentificationException` / `BATCH`, with stage and progress |

For batches, handle per-file errors in the callback and aborted calls in the
`catch` block, as shown [above](#ordered-batches).

## Reuse instances and shut down

Share one `Magika` instance across requests and threads. All identification
methods share its native session with separate per-call sampling, tensors and
results. The application owns scheduling, mutable inputs and callback state.
Creation also disables telemetry on the JVM-shared ORT environment, affecting
other users of that environment.

Create the instance at service startup. At shutdown, stop accepting application
requests, finish queued work, then call `close()`. Queuing a task does not admit
it to the SDK; admission occurs inside an identification method.
The [shared-instance example](examples/offline/src/main/java/example/SharedInstanceExample.java)
serves 16 requests on four application workers and then shuts down.

The lifecycle is **OPEN → CLOSING → CLOSED**. Closing rejects new identification
calls before reading input or consuming an iterator, waits for all accepted calls
to finish, then releases the session and its options. An accepted batch includes
all remaining inputs and callbacks. Results and `getModelInfo()` remain usable.

`close()` has no timeout and does not forcibly stop blocking input, callbacks or
native inference. Configure input-source timeouts and allow callbacks to finish.
Calling `close()` on the same thread inside an active call throws
`IllegalStateException` without changing the lifecycle. A callback must also
not wait for another thread to close the instance, which would create a deadlock.

Repeated or concurrent closes share one resource release and leave the JVM-shared
ORT environment available. An interrupted closer still waits and restores its
interrupt flag before exit. Release failures leave the instance closed, attempt
cleanup of the other owned resources and are reported again by later closes
without retrying release.

## Offline deployment and troubleshooting

Resolve and package the SDK **and its runtime dependencies** before disconnecting
from the network. The main JAR includes the model, configuration, type metadata,
asset manifest and notices. ONNX Runtime, Gson and its Error Prone annotations
dependency are separate JARs. Identification requires neither network access nor
Python and never downloads a model.

| Symptom or question | What to check |
| --- | --- |
| Native library fails to load | Inspect the `MODEL_INITIALIZATION` cause. ORT extracts native libraries to a writable temporary directory that must permit native loading. |
| Need to supply native libraries yourself | `-Donnxruntime.native.path=/path/to/libraries` points to an existing compatible native-library directory; it does not select an extraction directory. |
| A stream fails around 64 MiB | Check `maxStreamBytes`; raise the limit or finish saving the upload and identify its `Path`. |
| Content cannot be read again after identification | The stream was consumed. Arrange replay, reopen the source or save it before identification. |
| A result is `txt` or `unknown` | Inspect the raw prediction, mode and overwrite reason; empty/short-content rules may not run the model at all. |
| Shutting down waits indefinitely | Check blocking input and callbacks still running; configure their timeouts and completion in the application. |

Short-content rules use strict UTF-8 decoding, so invalid UTF-8 can yield
`unknown`. MIME strings preserve the bundled metadata, including `text-ocaml`
for OCaml.

## Run the examples

Clone the repository and run these commands from its root using JDK 21 and Maven.
This builds the standalone consumer against the published dependency:

```sh
mvn -B -ntp -f examples/offline/pom.xml clean package
java -cp 'examples/offline/target/offline-byte-array-1.0.0.jar:examples/offline/target/dependency/*' \
  example.OfflineExample
```

The classpath command targets the verified Linux environment. The consumer runs
byte-array, saved-upload, stream, batch, shared-instance and prediction-mode
examples. To use another installed JVM, replace `java` with its `bin/java`.

## Versions and further reading

`getModelInfo()` reports SDK version, model version, upstream commit and an
immutable asset-digest map. SDK **0.1.0** bundles model **standard_v3_3**; these are
separate version identifiers. Model assets ship with the SDK, and model/rule
updates require compatibility verification and a new SDK release.

Fixes increment the patch version. New capabilities, model/rule updates and
breaking 0.x API changes increment at least the minor version; breaking changes
include migration notes. Published coordinates are never replaced.

- [Supported labels and MIME types](docs/supported-types.md)
- [Development, verification and model provenance](docs/development.md) (English)
- [Release and publication guide](docs/releasing.md) (English)
- [0.1.0 acceptance record](docs/verification-issue-10.md) (English)
- [Performance measurements](docs/performance/issue-8-v1.md) (English; applies to the recorded environment and corpus)
- [Report an issue](https://github.com/zerocloud-sdk/magika/issues)

Public Javadoc is included in the published Javadoc JAR and generated locally at
`target/reports/apidocs/` when building the SDK.

## License

Own code and Google Magika adaptations/assets are under [Apache-2.0](LICENSE).
See [NOTICE](NOTICE) for upstream attribution. This SDK is independently
maintained by ZeroCloud SDK and is not a Google product.
