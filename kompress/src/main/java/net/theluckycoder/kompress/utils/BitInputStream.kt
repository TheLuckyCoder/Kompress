package net.theluckycoder.kompress.utils

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteOrder

/**
 * Reads bits from an InputStream.
 * @NotThreadSafe
 */
public open class BitInputStream(inputStream: InputStream, private val byteOrder: ByteOrder) : Closeable {

    private val countingInputStream: CountingInputStream = CountingInputStream(inputStream)
    private var bitsCached: Long = 0
    private var bitsCachedSize = 0

    @Throws(IOException::class)
    override fun close() {
        countingInputStream.close()
    }

    /**
     * Clears the cache of bits that have been read from the
     * underlying stream but not yet provided via [readBits].
     */
    public fun clearBitCache() {
        bitsCached = 0
        bitsCachedSize = 0
    }

    /**
     * Returns at most 63 bits read from the underlying stream.
     *
     * @param count the number of bits to read, must be a positive
     * number not bigger than 63.
     * @return the bits concatenated as a long using the stream's byte order.
     * -1 if the end of the underlying stream has been reached before reading
     * the requested number of bits
     * @throws IOException on error
     */
    @Throws(IOException::class)
    public fun readBits(count: Int): Long {
        require(!(count < 0 || count > MAXIMUM_CACHE_SIZE)) { "count must not be negative or greater than $MAXIMUM_CACHE_SIZE" }
        if (ensureCache(count)) {
            return -1
        }
        return if (bitsCachedSize < count) {
            processBitsGreater57(count)
        } else readCachedBits(count)
    }

    /**
     * Returns the number of bits that can be read from this input
     * stream without reading from the underlying input stream at all.
     * @return estimate of the number of bits that can be read without reading from the underlying stream
     */
    public fun bitsCached(): Int = bitsCachedSize

    /**
     * Returns an estimate of the number of bits that can be read from
     * this input stream without blocking by the next invocation of a
     * method for this input stream.
     * @throws IOException if the underlying stream throws one when calling available
     * @return estimate of the number of bits that can be read without blocking
     */
    @Throws(IOException::class)
    public fun bitsAvailable(): Long =
        bitsCachedSize + java.lang.Byte.SIZE.toLong() * countingInputStream.available()

    /**
     * Drops bits until the next bits will be read from a byte boundary.
     */
    public fun alignWithByteBoundary() {
        val toSkip = bitsCachedSize % java.lang.Byte.SIZE
        if (toSkip > 0)
            readCachedBits(toSkip)
    }

    /**
     * Returns the number of bytes read from the underlying stream.
     *
     *
     * This includes the bytes read to fill the current cache and
     * not read as bits so far.
     * @return the number of bytes read from the underlying stream
     */
    public val bytesRead: Long
        get() = countingInputStream.bytesRead

    @Throws(IOException::class)
    private fun processBitsGreater57(count: Int): Long {
        val overflowBits: Int
        val overflow: Long

        // bitsCachedSize >= 57 and left-shifting it 8 bits would cause an overflow
        val bitsToAddCount = count - bitsCachedSize
        overflowBits = java.lang.Byte.SIZE - bitsToAddCount
        val nextByte = countingInputStream.read().toLong()
        if (nextByte < 0) {
            return nextByte
        }
        if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
            val bitsToAdd = nextByte and MASKS[bitsToAddCount]
            bitsCached = bitsCached or (bitsToAdd shl bitsCachedSize)
            overflow = nextByte ushr bitsToAddCount and MASKS[overflowBits]
        } else {
            bitsCached = bitsCached shl bitsToAddCount
            val bitsToAdd = nextByte ushr overflowBits and MASKS[bitsToAddCount]
            bitsCached = bitsCached or bitsToAdd
            overflow = nextByte and MASKS[overflowBits]
        }
        val bitsOut: Long = bitsCached and MASKS[count]
        bitsCached = overflow
        bitsCachedSize = overflowBits
        return bitsOut
    }

    private fun readCachedBits(count: Int): Long {
        val bitsOut: Long
        if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
            bitsOut = bitsCached and MASKS[count]
            bitsCached = bitsCached ushr count
        } else {
            bitsOut = bitsCached shr bitsCachedSize - count and MASKS[count]
        }
        bitsCachedSize -= count
        return bitsOut
    }

    /**
     * Fills the cache up to 56 bits
     * @param count
     * @return return true, when EOF
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun ensureCache(count: Int): Boolean {
        while (bitsCachedSize < count && bitsCachedSize < 57) {
            val nextByte = countingInputStream.read().toLong()
            if (nextByte < 0) {
                return true
            }
            if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
                bitsCached = bitsCached or (nextByte shl bitsCachedSize)
            } else {
                bitsCached = bitsCached shl java.lang.Byte.SIZE
                bitsCached = bitsCached or nextByte
            }
            bitsCachedSize += java.lang.Byte.SIZE
        }
        return false
    }

    public companion object {
        private const val MAXIMUM_CACHE_SIZE = 63 // bits in long minus sign bit
        private val MASKS = LongArray(MAXIMUM_CACHE_SIZE + 1)

        init {
            for (i in 1..MAXIMUM_CACHE_SIZE)
                MASKS[i] = (MASKS[i - 1] shl 1) + 1
        }
    }
}
