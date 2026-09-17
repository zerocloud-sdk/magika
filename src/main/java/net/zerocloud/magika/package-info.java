/**
 * Offline content identification with an embedded, fixed Magika model.
 * The public API and runtime dependencies support Java 8. Instances can be
 * shared for concurrent identification. Close rejects new calls and waits for
 * accepted synchronous calls, including entire batches and their callbacks,
 * before releasing native resources. Callbacks execute without the lifecycle
 * lock and must not wait for another thread to close the same instance.
 * Same-thread reentrant close is rejected. Close does not forcibly terminate
 * input reads, callbacks or native inference; see {@link net.zerocloud.magika.Magika#close()}.
 */
package net.zerocloud.magika;
