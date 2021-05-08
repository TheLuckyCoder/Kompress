package net.theluckycoder.kompress.zip

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CharsetEncoder
import java.nio.charset.CodingErrorAction
import kotlin.math.ceil

/**
 * A [ZipEncoding], which uses a java.nio to encode names.
 *
 * The methods of this class are reentrant.
 * @Immutable
 */
internal class NioZipEncoding(
    /**
     * The character set to use.
     */
    private val charset: Charset,
    /**
     * should invalid characters be replaced, or reported.
     */
    private val useReplacement: Boolean
) : ZipEncoding, CharsetAccessor {
    override fun getCharset(): Charset {
        return charset
    }

    /**
     * @see ZipEncoding.canEncode
     */
    override fun canEncode(name: String?): Boolean {
        val enc = newEncoder()
        return enc.canEncode(name)
    }

    /**
     * @see ZipEncoding.encode
     */
    override fun encode(name: String): ByteBuffer {
        val enc = newEncoder()
        val cb = CharBuffer.wrap(name)
        var tmp: CharBuffer? = null
        var out = ByteBuffer.allocate(estimateInitialBufferSize(enc, cb.remaining()))
        while (cb.hasRemaining()) {
            val res = enc.encode(cb, out, false)
            if (res.isUnmappable || res.isMalformed) {

                // write the unmappable characters in utf-16
                // pseudo-URL encoding style to ByteBuffer.
                val spaceForSurrogate = estimateIncrementalEncodingSize(enc, 6 * res.length())
                if (spaceForSurrogate > out.remaining()) {
                    // if the destination buffer isn't over sized, assume that the presence of one
                    // unmappable character makes it likely that there will be more. Find all the
                    // un-encoded characters and allocate space based on those estimates.
                    var charCount = 0
                    for (i in cb.position() until cb.limit()) {
                        charCount += if (!enc.canEncode(cb[i])) 6 else 1
                    }
                    val totalExtraSpace = estimateIncrementalEncodingSize(enc, charCount)
                    out = ZipEncodingHelper.growBufferBy(out, totalExtraSpace - out.remaining())
                }
                if (tmp == null) {
                    tmp = CharBuffer.allocate(6)
                }
                for (i in 0 until res.length()) {
                    out = encodeFully(enc, encodeSurrogate(tmp!!, cb.get()), out)
                }
            } else if (res.isOverflow) {
                val increment = estimateIncrementalEncodingSize(enc, cb.remaining())
                out = ZipEncodingHelper.growBufferBy(out, increment)
            } else if (res.isUnderflow || res.isError) {
                break
            }
        }
        // tell the encoder we are done
        enc.encode(cb, out, true)
        // may have caused underflow, but that's been ignored traditionally
        out.limit(out.position())
        out.rewind()
        return out
    }

    @Throws(IOException::class)
    override fun decode(data: ByteArray): String =
        newDecoder().decode(ByteBuffer.wrap(data)).toString()

    private fun newEncoder(): CharsetEncoder {
        return if (useReplacement) {
            charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith(REPLACEMENT_BYTES)
        } else {
            charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        }
    }

    private fun newDecoder(): CharsetDecoder {
        return if (!useReplacement) {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        } else {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith(REPLACEMENT_STRING)
        }
    }

    companion object {
        private const val REPLACEMENT = '?'
        private val REPLACEMENT_BYTES = byteArrayOf(REPLACEMENT.code.toByte())
        private const val REPLACEMENT_STRING = REPLACEMENT.toString()
        private val HEX_CHARS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
        )

        private fun encodeFully(enc: CharsetEncoder, cb: CharBuffer, out: ByteBuffer): ByteBuffer {
            var o = out
            while (cb.hasRemaining()) {
                val result = enc.encode(cb, o, false)
                if (result.isOverflow) {
                    val increment = estimateIncrementalEncodingSize(enc, cb.remaining())
                    o = ZipEncodingHelper.growBufferBy(o, increment)
                }
            }
            return o
        }

        private fun encodeSurrogate(cb: CharBuffer, c: Char): CharBuffer {
            cb.position(0).limit(6)
            cb.put('%')
            cb.put('U')
            cb.put(HEX_CHARS[c.code shr 12 and 0x0f])
            cb.put(HEX_CHARS[c.code shr 8 and 0x0f])
            cb.put(HEX_CHARS[c.code shr 4 and 0x0f])
            cb.put(HEX_CHARS[c.code and 0x0f])
            cb.flip()
            return cb
        }

        /**
         * Estimate the initial encoded size (in bytes) for a character buffer.
         *
         *
         * The estimate assumes that one character consumes uses the maximum length encoding,
         * whilst the rest use an average size encoding. This accounts for any BOM for UTF-16, at
         * the expense of a couple of extra bytes for UTF-8 encoded ASCII.
         *
         *
         * @param enc        encoder to use for estimates
         * @param charCount number of characters in string
         * @return estimated size in bytes.
         */
        private fun estimateInitialBufferSize(enc: CharsetEncoder, charCount: Int): Int {
            val first = enc.maxBytesPerChar()
            val rest = (charCount - 1) * enc.averageBytesPerChar()
            return ceil((first + rest).toDouble()).toInt()
        }

        /**
         * Estimate the size needed for remaining characters
         *
         * @param enc       encoder to use for estimates
         * @param charCount number of characters remaining
         * @return estimated size in bytes.
         */
        private fun estimateIncrementalEncodingSize(enc: CharsetEncoder, charCount: Int): Int =
            ceil((charCount * enc.averageBytesPerChar()).toDouble()).toInt()
    }
}
