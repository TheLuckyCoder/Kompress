package net.theluckycoder.kompress.utils

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Input stream that tracks the number of bytes read.
 * @NotThreadSafe
 */
public open class CountingInputStream(inputStream: InputStream) : FilterInputStream(inputStream) {

    /**
     * Returns the current number of bytes read from this stream.
     * @return the number of read bytes
     */
    public var bytesRead: Long = 0
        private set

    @Throws(IOException::class)
    override fun read(): Int {
        val r = `in`.read()
        if (r >= 0)
            count(1)
        return r
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray): Int {
        return read(b, 0, b.size)
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) {
            return 0
        }
        val r = `in`.read(b, off, len)
        if (r >= 0)
            count(r.toLong())

        return r
    }

    /**
     * Increments the counter of already read bytes.
     * Doesn't increment if the EOF has been hit (read == -1)
     *
     * @param read the number of bytes read
     */
    protected fun count(read: Long) {
        if (read != -1L)
            bytesRead += read
    }
}
