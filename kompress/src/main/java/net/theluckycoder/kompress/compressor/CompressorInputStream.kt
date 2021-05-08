package net.theluckycoder.kompress.compressor

import java.io.InputStream

public abstract class CompressorInputStream : InputStream() {

    /**
     * Returns the current number of bytes read from this stream.
     * @return the number of read bytes
     */
    public var bytesRead: Long = 0
        private set

    /**
     * Increments the counter of already read bytes.
     * Doesn't increment if the EOF has been hit (read == -1)
     *
     * @param read the number of bytes read
     */
    protected fun count(read: Int) {
        count(read.toLong())
    }

    /**
     * Increments the counter of already read bytes.
     * Doesn't increment if the EOF has been hit (read == -1)
     *
     * @param read the number of bytes read
     */
    protected fun count(read: Long) {
        if (read != -1L) {
            bytesRead += read
        }
    }

    /**
     * Decrements the counter of already read bytes.
     *
     * @param pushedBack the number of bytes pushed back.
     */
    protected fun pushedBackBytes(pushedBack: Long) {
        bytesRead -= pushedBack
    }

    /**
     * Returns the amount of raw or compressed bytes read by the stream.
     *
     *
     * This implementation invokes [bytesRead].
     *
     * @return the amount of decompressed bytes returned by the stream
     */
    public val uncompressedCount: Long
        get() = bytesRead
}
