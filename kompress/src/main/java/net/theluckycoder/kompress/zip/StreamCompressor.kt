package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.parallel.ScatterGatherBackingStore
import java.io.Closeable
import java.io.DataOutput
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry

/**
 * Encapsulates a [Deflater] and crc calculator, handling multiple types of output streams.
 * Currently [ZipEntry.DEFLATED] and [ZipEntry.STORED] are the only
 * supported compression methods.
 */
public abstract class StreamCompressor internal constructor(private val def: Deflater) : Closeable {

    private val crc = CRC32()

    /**
     * The number of bytes written to the output for the last entry
     *
     * @return The number of bytes, never negative
     */
    public var bytesWrittenForLastEntry: Long = 0L
        private set

    /**
     * Return the number of bytes read from the source stream
     *
     * @return The number of bytes read, never negative
     */
    public var bytesRead: Long = 0L
        private set

    /**
     * The total number of bytes written to the output for all files
     *
     * @return The number of bytes, never negative
     */
    public var totalBytesWritten: Long = 0L
        private set
    private val outputBuffer = ByteArray(BUFFER_SIZE)
    private val readerBuf = ByteArray(BUFFER_SIZE)

    /**
     * The crc32 of the last deflated file
     *
     * @return the crc32
     */
    public val crc32: Long
        get() = crc.value

    /**
     * Deflate the given source using the supplied compression method
     *
     * @param source The source to compress
     * @param method The #ZipArchiveEntry compression method
     * @throws IOException When failures happen
     */
    @Throws(IOException::class)
    public fun deflate(source: InputStream, method: Int) {
        reset()
        var length: Int
        while (source.read(readerBuf, 0, readerBuf.size).also { length = it } >= 0) {
            write(readerBuf, 0, length, method)
        }
        if (method == ZipEntry.DEFLATED) {
            flushDeflater()
        }
    }

    /**
     * Writes bytes to ZIP entry.
     *
     * @param b      the byte array to write
     * @param offset the start position to write from
     * @param length the number of bytes to write
     * @param method the compression method to use
     * @return the number of bytes written to the stream this time
     * @throws IOException on error
     */
    @Throws(IOException::class)
    public fun write(b: ByteArray, offset: Int, length: Int, method: Int): Long {
        val current = bytesWrittenForLastEntry
        crc.update(b, offset, length)
        if (method == ZipEntry.DEFLATED) {
            writeDeflated(b, offset, length)
        } else {
            writeCounted(b, offset, length)
        }
        bytesRead += length.toLong()
        return bytesWrittenForLastEntry - current
    }

    public fun reset() {
        crc.reset()
        def.reset()
        bytesRead = 0
        bytesWrittenForLastEntry = 0
    }

    override fun close() {
        def.end()
    }

    @Throws(IOException::class)
    public fun flushDeflater() {
        def.finish()
        while (!def.finished()) {
            deflate()
        }
    }

    @Throws(IOException::class)
    private fun writeDeflated(b: ByteArray, offset: Int, length: Int) {
        if (length > 0 && !def.finished()) {
            if (length <= DEFLATER_BLOCK_SIZE) {
                def.setInput(b, offset, length)
                deflateUntilInputIsNeeded()
            } else {
                val fullBlocks = length / DEFLATER_BLOCK_SIZE
                for (i in 0 until fullBlocks) {
                    def.setInput(
                        b, offset + i * DEFLATER_BLOCK_SIZE,
                        DEFLATER_BLOCK_SIZE
                    )
                    deflateUntilInputIsNeeded()
                }
                val done = fullBlocks * DEFLATER_BLOCK_SIZE
                if (done < length) {
                    def.setInput(b, offset + done, length - done)
                    deflateUntilInputIsNeeded()
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun deflateUntilInputIsNeeded() {
        while (!def.needsInput()) {
            deflate()
        }
    }

    @Throws(IOException::class)
    public fun deflate() {
        val len = def.deflate(outputBuffer, 0, outputBuffer.size)
        if (len > 0)
            writeCounted(outputBuffer, 0, len)
    }

    @Throws(IOException::class)
    public fun writeCounted(data: ByteArray) {
        writeCounted(data, 0, data.size)
    }

    @Throws(IOException::class)
    public fun writeCounted(data: ByteArray, offset: Int, length: Int) {
        writeOut(data, offset, length)
        bytesWrittenForLastEntry += length.toLong()
        totalBytesWritten += length.toLong()
    }

    @Throws(IOException::class)
    internal abstract fun writeOut(data: ByteArray, offset: Int, length: Int)

    private class ScatterGatherBackingStoreCompressor(deflater: Deflater, private val bs: ScatterGatherBackingStore) :
        StreamCompressor(deflater) {

        override fun writeOut(data: ByteArray, offset: Int, length: Int) {
            bs.writeOut(data, offset, length)
        }
    }

    private class OutputStreamCompressor(deflater: Deflater, private val os: OutputStream) :
        StreamCompressor(deflater) {

        override fun writeOut(data: ByteArray, offset: Int, length: Int) {
            os.write(data, offset, length)
        }
    }

    private class DataOutputCompressor(deflater: Deflater, private val raf: DataOutput) : StreamCompressor(deflater) {

        override fun writeOut(data: ByteArray, offset: Int, length: Int) {
            raf.write(data, offset, length)
        }
    }

    private class FileChannelCompressor(
        deflater: Deflater,
        private val channel: FileChannel
    ) : StreamCompressor(deflater) {

        override fun writeOut(data: ByteArray, offset: Int, length: Int) {
            channel.write(ByteBuffer.wrap(data, offset, length))
        }
    }

    public companion object {
        /*
         * Apparently Deflater.setInput gets slowed down a lot on Sun JVMs
         * when it gets handed a really big buffer.  See
         * https://issues.apache.org/bugzilla/show_bug.cgi?id=45396
         *
         * Using a buffer size of 8 kB proved to be a good compromise
         */
        private const val DEFLATER_BLOCK_SIZE = 8192
        private const val BUFFER_SIZE = 4096

        /**
         * Create a stream compressor with the given compression level.
         *
         * @param os       The stream to receive output
         * @param deflater The deflater to use
         * @return A stream compressor
         */
        @JvmOverloads
        public fun create(
            os: OutputStream,
            deflater: Deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        ): StreamCompressor {
            return OutputStreamCompressor(deflater, os)
        }

        /**
         * Create a stream compressor with the given compression level.
         *
         * @param os       The DataOutput to receive output
         * @param deflater The deflater to use for the compressor
         * @return A stream compressor
         */
        public fun create(os: DataOutput, deflater: Deflater): StreamCompressor {
            return DataOutputCompressor(deflater, os)
        }

        /**
         * Create a stream compressor with the given compression level.
         *
         * @param os       The FileChannel to receive output
         * @param deflater The deflater to use for the compressor
         * @return A stream compressor
         */
        public fun create(os: FileChannel, deflater: Deflater): StreamCompressor {
            return FileChannelCompressor(deflater, os)
        }

        /**
         * Create a stream compressor with the given compression level.
         *
         * @param compressionLevel The [Deflater]  compression level
         * @param bs               The ScatterGatherBackingStore to receive output
         * @return A stream compressor
         */
        public fun create(compressionLevel: Int, bs: ScatterGatherBackingStore): StreamCompressor {
            val deflater = Deflater(compressionLevel, true)
            return ScatterGatherBackingStoreCompressor(deflater, bs)
        }

        /**
         * Create a stream compressor with the default compression level.
         *
         * @param bs The ScatterGatherBackingStore to receive output
         * @return A stream compressor
         */
        public fun create(bs: ScatterGatherBackingStore): StreamCompressor {
            return create(Deflater.DEFAULT_COMPRESSION, bs)
        }
    }
}
