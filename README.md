# Magika Java SDK

An independently maintained Java 8 SDK by ZeroCloud SDK. It identifies complete
byte arrays offline using Google's bundled `standard_v3_3` Magika model and the
official ONNX Runtime CPU dependency, version `1.30.0`.

This implements [issue #2](https://github.com/zerocloud-sdk/magika/issues/2) and
[issue #3](https://github.com/zerocloud-sdk/magika/issues/3): sequential byte array
identification with all three official prediction modes, defaulting to
**HIGH_CONFIDENCE**. File/stream/batch APIs, concurrent use and release
publication are separate tickets. Version `0.1.0` here is a local build, not a claim
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

Reuse an instance for sequential calls. The caller must serialize calls to
`identify` and `close` in this version. Close the instance with try-with-resources;
closing releases the Session before SessionOptions and leaves the JVM-shared
OrtEnvironment alone. Repeated sequential close is harmless. Identification after
close throws `IllegalStateException`. `getModelInfo()` remains usable after close.

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

`DetectionResult`, `RawPrediction`, and `ModelInfo` are immutable Java values.
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
| Null input, null prediction mode or nonpositive thread count | `IllegalArgumentException` |
| Identification after close | `IllegalStateException` |
| Missing, corrupt or inconsistent assets | `MagikaException`, category `ASSET_VALIDATION` |
| Native loading, Session initialization or signature failure | `MagikaException`, category `MODEL_INITIALIZATION` |
| Inference/output failure | `MagikaException`, category `INFERENCE` |
| Instance resource-release failure | `MagikaException`, category `RESOURCE_RELEASE` |

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
thread configuration, and sequential native resource reuse. Javadoc generation
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
The [prediction-mode verification record](docs/verification-issue-3.md) and
[initial SDK verification record](docs/verification-issue-2.md) record executed checks and
limits. Platform evidence is limited to Ubuntu 24.04 x64 / CPU; other distributions,
architectures, libc versions and GPU execution are not verified.

The [asset manifest](src/main/resources/net/zerocloud/magika/model/asset-manifest.json)
records the fixed upstream commit, model contract, exact byte sizes, sources,
SHA-256 digests, license, and both static reference datasets. Reference input bytes
are embedded in the original fixtures; these fixtures are test-only. The 9,261
feature cases use head 128, tail 64 and window 512; separate boundary tests cover
the actual model's 1024/1024 and 4096 configuration. All 141 content cases are
checked through the public API, exactly 47 per mode, with exact raw/final labels,
overwrite reasons and MIME strings, and absolute score error at most `1e-5`.
Neither these cases nor the deterministic rule tests
establish accuracy for all 214 model classes.

Before upgrading the model or its companion configuration/metadata, pin the new
asset set and upstream reference sources together, then rerun compatibility
acceptance:

- All official feature references (currently 9,261), plus actual model window,
  padding, whitespace and strict UTF-8 boundaries.
- All content references in each of the three modes (currently 141, 47 per mode),
  checking raw/final labels, reasons, MIME and the `1e-5` score tolerance.
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
