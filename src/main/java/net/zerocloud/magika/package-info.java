/**
 * Offline content identification with an embedded, fixed Magika model.
 * The public API and runtime dependencies support Java 8. Instances in this
 * version are for sequential use; callers must serialize identify, the entire
 * identifyAll call and close. Batch callbacks run synchronously without locks.
 */
package net.zerocloud.magika;
