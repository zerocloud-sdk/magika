# Magika Java SDK

An independently maintained Java 8 SDK by ZeroCloud SDK. It identifies complete
byte arrays offline using Google's bundled `standard_v3_3` Magika model and the
official ONNX Runtime CPU dependency, version `1.30.0`.

This implements [issue #2](https://github.com/zerocloud-sdk/magika/issues/2):
sequential byte array identification with the default **HIGH_CONFIDENCE** policy.
The remaining prediction modes, file/stream/batch APIs, concurrent use and release
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

The fixed upstream rules use strict UTF-8 decoding for short content; invalid
UTF-8 returns `unknown`. Long leading whitespace can also select the short-content
rule using only the first 4096-byte window. Leading head whitespace and trailing
tail whitespace use exactly Python's six ASCII byte whitespace values. Metadata
strings are preserved verbatim, including upstream's `text-ocaml` for OCaml.

| Failure | Public exception |
| --- | --- |
| Null input or nonpositive thread count | `IllegalArgumentException` |
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
The [verification record](docs/verification-issue-2.md) records executed checks and
limits. Platform evidence is limited to Ubuntu 24.04 x64 / CPU; other distributions,
architectures, libc versions and GPU execution are not verified.

The [asset manifest](src/main/resources/net/zerocloud/magika/model/asset-manifest.json)
records the fixed upstream commit, model contract, exact byte sizes, sources,
SHA-256 digests, license, and both static reference datasets. Reference input bytes
are embedded in the original fixtures; these fixtures are test-only. The 9,261
feature cases use head 128, tail 64 and window 512; separate boundary tests cover
the actual model's 1024/1024 and 4096 configuration. Only the 47 HIGH_CONFIDENCE
content cases are in scope. Neither these cases nor the deterministic rule tests
establish accuracy for all 214 model classes.

`getModelInfo()` separately reports SDK version, model version, upstream commit and
an unmodifiable filename-to-SHA-256 map. Model or rule changes require compatibility
verification and a new SDK version; the model is not a separate Maven artifact.
Version policy: fixes increment the patch; new capabilities, model/rule updates,
and breaking 0.x APIs increment at least the minor version, with migration notes
for breaking changes. Published coordinates must never be replaced.

Public Javadoc is generated in `target/reports/apidocs/` and the Javadoc JAR.
Own code and Google Magika adaptations/assets are under [Apache-2.0](LICENSE);
see [NOTICE](NOTICE) for upstream attribution. This SDK is not a Google product.
