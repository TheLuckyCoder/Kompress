package net.theluckycoder.kompress.utils

/**
 * This interface provides statistics on the current decompression stream.
 * The stream consumer can use that statistics to handle abnormal
 * compression ratios, i.e. to prevent zip bombs.
 */
public interface InputStreamStatistics {

    /**
     * The amount of raw or compressed bytes read by the stream
     */
    public val compressedCount: Long

    /**
     * The amount of decompressed bytes returned by the stream
     */
    public val uncompressedCount: Long
}
