package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.utils.InputStreamStatistics
import java.io.IOException
import java.io.InputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Helper class to provide statistics
 */
internal open class InflaterInputStreamWithStatistics : InflaterInputStream, InputStreamStatistics {

    override var compressedCount: Long = 0
        protected set
    override var uncompressedCount: Long = 0
        protected set

    constructor(inputStream: InputStream) : super(inputStream)
    constructor(inputStream: InputStream, inflater: Inflater) : super(inputStream, inflater)
    constructor(inputStream: InputStream, inflater: Inflater, size: Int) : super(inputStream, inflater, size)

    @Throws(IOException::class)
    override fun fill() {
        super.fill()
        compressedCount += inf.remaining.toLong()
    }

    @Throws(IOException::class)
    override fun read(): Int {
        val b = super.read()
        if (b > -1)
            uncompressedCount++

        return b
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val bytes = super.read(b, off, len)
        if (bytes > -1)
            uncompressedCount += bytes.toLong()

        return bytes
    }
}
