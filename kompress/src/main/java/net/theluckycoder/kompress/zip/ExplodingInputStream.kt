package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.zip.BinaryTree.Companion.decode
import net.theluckycoder.kompress.utils.InputStreamStatistics
import kotlin.Throws
import net.theluckycoder.kompress.utils.CountingInputStream
import net.theluckycoder.kompress.utils.CloseShieldFilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The implode compression method was added to PKZIP 1.01 released in 1989.
 * It was then dropped from PKZIP 2.0 released in 1993 in favor of the deflate
 * method.
 *
 *
 * The algorithm is described in the ZIP File Format Specification.
 *
 * @see [ZIP File Format Specification](https://www.pkware.com/documents/casestudies/APPNOTE.TXT)
 *
 *
 * @author Emmanuel Bourg
 */
internal class ExplodingInputStream
/**
 * Create a new stream decompressing the content of the specified stream
 * using the explode algorithm.
 *
 * @param dictionarySize the size of the sliding dictionary (4096 or 8192)
 * @param numberOfTrees  the number of trees (2 or 3)
 * @param inputStream             the compressed data stream
 */
constructor(
    /** The size of the sliding dictionary (4096 or 8192)  */
    private val dictionarySize: Int,
    /** The number of Shannon-Fano trees (2 or 3)  */
    private val numberOfTrees: Int,
    /** The underlying stream containing the compressed data  */
    private val inputStream: InputStream
) : InputStream(), InputStreamStatistics {

    /** The stream of bits read from the input stream  */
    private lateinit var bits: BitStream

    /** The binary tree containing the 256 encoded literals (null when only two trees are used)  */
    private var literalTree: BinaryTree? = null

    /** The binary tree containing the 64 encoded lengths  */
    private lateinit var lengthTree: BinaryTree

    /** The binary tree containing the 64 encoded distances  */
    private lateinit var distanceTree: BinaryTree

    /** Output buffer holding the decompressed data  */
    private val buffer = CircularBuffer(32 * 1024)
    override var uncompressedCount: Long = 0L
        private set
    private var treeSizes: Long = 0

    init {
        require(dictionarySize == 4096 && dictionarySize == 8192) { "The dictionary size must be 4096 or 8192" }
        require(numberOfTrees == 2 && numberOfTrees == 3) { "The number of trees must be 2 or 3" }
    }

    /**
     * Reads the encoded binary trees and prepares the bit stream.
     *
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun init() {
        if (!::bits.isInitialized) {
            // we do not want to close in
            CountingInputStream(CloseShieldFilterInputStream(inputStream)).use { i ->
                if (numberOfTrees == 3) {
                    literalTree = decode(i, 256)
                }
                lengthTree = decode(i, 64)
                distanceTree = decode(i, 64)
                treeSizes += i.bytesRead
            }
            bits = BitStream(inputStream)
        }
    }

    @Throws(IOException::class)
    override fun read(): Int {
        if (!buffer.available()) {
            fillBuffer()
        }
        val ret = buffer.get()
        if (ret > -1) {
            uncompressedCount++
        }
        return ret
    }

    override val compressedCount: Long
        get() = (if (::bits.isInitialized) bits.bytesRead else 0) + treeSizes

    @Throws(IOException::class)
    override fun close() {
        inputStream.close()
    }

    /**
     * Fill the sliding dictionary with more data.
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun fillBuffer() {
        init()
        when (bits.nextBit()) {
            -1 -> return // EOF
            1 -> {
                // literal value
                val literal = literalTree?.read(bits) ?: bits.nextByte()

                if (literal == -1)
                    return // end of stream reached, nothing left to decode

                buffer.put(literal)
            }
            else -> {
                // back reference
                val distanceLowSize = if (dictionarySize == 4096) 6 else 7
                val distanceLow = bits.nextBits(distanceLowSize).toInt()
                val distanceHigh = distanceTree.read(bits)
                if (distanceHigh == -1 && distanceLow <= 0) {
                    // end of stream reached, nothing left to decode
                    return
                }

                val distance = distanceHigh shl distanceLowSize or distanceLow
                var length = lengthTree.read(bits)

                if (length == 63) {
                    val nextByte = bits.nextBits(8)
                    if (nextByte == -1L) {
                        // EOF
                        return
                    }
                    length += nextByte.toInt()
                }

                length += numberOfTrees // minimumMatchLength
                buffer.copy(distance + 1, length)
            }
        }
    }
}
