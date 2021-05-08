package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.utils.BitInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteOrder

/**
 * Iterates over the bits of an InputStream. For each byte the bits
 * are read from the right to the left.
 */
internal class BitStream(inputStream: InputStream) : BitInputStream(inputStream, ByteOrder.LITTLE_ENDIAN) {

    /**
     * Returns the next bit.
     *
     * @return The next bit (0 or 1) or -1 if the end of the stream has been reached
     */
    @Throws(IOException::class)
    fun nextBit(): Int = readBits(1).toInt()

    /**
     * Returns the integer value formed by the n next bits (up to 8 bits).
     *
     * @param n the number of bits read (up to 8)
     * @return The value formed by the n bits, or -1 if the end of the stream has been reached
     */
    @Throws(IOException::class)
    fun nextBits(n: Int): Long {
        if (n < 0 || n > 8)
            throw IOException("Trying to read $n bits, at most 8 are allowed")

        return readBits(n)
    }

    @Throws(IOException::class)
    fun nextByte(): Int = readBits(8).toInt()
}
