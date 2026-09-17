/**
 * Offline content identification with an embedded, fixed Magika model.
 * The public API and runtime dependencies support Java 8. Instances can be
 * shared for concurrent identification. Close rejects new calls and waits for
 * accepted synchronous calls, including entire batches and their callbacks,
 * before releasing native resources. Callbacks execute without the lifecycle
 * lock and must not wait for another thread to close the same instance.
 * Same-thread reentrant close is rejected. Close does not forcibly terminate
 * input reads, callbacks or native inference; see {@link net.zerocloud.magika.Magika#close()}.
 *
 * <p>{@link net.zerocloud.magika.Magika#identify(byte[])} accepts complete,
 * caller-owned content that must stay unchanged until the call returns.
 * {@link net.zerocloud.magika.Magika#identify(java.nio.file.Path)} follows links to
 * regular files, owns and closes its read handle, samples fixed windows and has
 * no default total file size limit. The caller must keep the file stable.
 * {@link net.zerocloud.magika.Magika#identify(java.io.InputStream)} reads from the
 * current position to EOF without closing or resetting the caller's stream.
 * The default stream limit is 64 MiB; exceeding it fails instead of identifying
 * truncated input. Sampling buffers are bounded independently of input length;
 * this excludes model, native runtime and caller-owned memory.
 *
 * <p>{@link net.zerocloud.magika.DetectionResult} distinguishes raw predictions
 * from final labels and explains mapping and confidence fallback. Model scores
 * retain the raw top-1 score, not the probability of a rewritten MIME type.
 * Empty/short-content rules have score 1.0 and no raw prediction. Unknown content
 * is a successful result, distinct from {@link net.zerocloud.magika.MagikaException}.
 * Batch file failures are delivered individually; iterator, callback and system
 * failures abort with the already-delivered prefix recorded in
 * {@link net.zerocloud.magika.BatchIdentificationException}. Callback side effects
 * are never rolled back or retried automatically.
 *
 * <p>The model and its companion configuration and metadata are embedded together;
 * dependency installation is sufficient for offline use, without Python or model
 * downloads. {@link net.zerocloud.magika.ModelInfo} reports independent SDK and
 * model versions, fixed upstream source and asset digests. Model/rule upgrades
 * require compatibility validation and a new SDK release. The verified platform
 * is Ubuntu 24.04 x64 / CPU with Java 8, 17 and 21.
 *
 * <p>ONNX Runtime 1.30.0 loads its native library through JNI. Its default loader
 * needs a writable temporary directory that permits native loading; alternatively,
 * {@code onnxruntime.native.path} names an existing compatible native-library
 * directory. It does not select an extraction directory. Native load or model
 * initialization failures retain their cause in a model-initialization exception.
 */
package net.zerocloud.magika;
