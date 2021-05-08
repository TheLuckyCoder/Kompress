package net.theluckycoder.kompress.utils

import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/**
 * A stream that limits reading from a wrapped stream to a given number of bytes.
 * @NotThreadSafe
 *
 * Creates the stream that will at most read the given amount of
 * bytes from the given stream.
 * @param inputStream the stream to read from
 * @param bytesRemaining the maximum amount of bytes to read
 */
public class BoundedInputStream(private val inputStream: InputStream, private var bytesRemaining: Long) :
    InputStream() {

    @Throws(IOException::class)
    override fun read(): Int {
        if (bytesRemaining > 0) {
            --bytesRemaining
            return inputStream.read()
        }
        return -1
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0)
            return 0

        if (bytesRemaining == 0L)
            return -1

        var bytesToRead = len
        if (bytesToRead > bytesRemaining)
            bytesToRead = bytesRemaining.toInt()

        val bytesRead = inputStream.read(b, off, bytesToRead)
        if (bytesRead >= 0)
            bytesRemaining -= bytesRead.toLong()

        return bytesRead
    }

    override fun close() {
        // there isn't anything to close in this stream and the nested
        // stream is controlled externally
    }

    @Throws(IOException::class)
    override fun skip(n: Long): Long {
        val bytesToSkip = min(bytesRemaining, n)
        val bytesSkipped = inputStream.skip(bytesToSkip)
        bytesRemaining -= bytesSkipped
        return bytesSkipped
    }
}
