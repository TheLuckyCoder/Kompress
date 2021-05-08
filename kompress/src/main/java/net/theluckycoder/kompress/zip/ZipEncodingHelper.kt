package net.theluckycoder.kompress.zip

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.charset.UnsupportedCharsetException

/**
 * Static helper functions for robustly encoding file names in zip files.
 */
internal object ZipEncodingHelper {
    /**
     * name of the encoding UTF-8
     */
    const val UTF8 = "UTF8"

    /**
     * the encoding UTF-8
     */
    @JvmField
    val UTF8_ZIP_ENCODING = getZipEncoding(UTF8)

    /**
     * Instantiates a zip encoding. An NIO based character set encoder/decoder will be returned.
     * As a special case, if the character set is UTF-8, the nio encoder will be configured  replace malformed and
     * un-mappable characters with '?'. This matches existing behavior from the older fallback encoder.
     *
     *
     * If the requested [Charset] cannot be found, the platform default will
     * be used instead.
     *
     * @param name The name of the zip encoding. Specify `null` for
     * the platform's default encoding.
     * @return A zip encoding for the given encoding name.
     */
    fun getZipEncoding(name: String?): ZipEncoding {
        var cs = Charset.defaultCharset()
        if (name != null) {
            try {
                cs = Charset.forName(name)
            } catch (e: UnsupportedCharsetException) { // we use the default encoding instead
            }
        }
        val useReplacement = isUTF8(cs.name())
        return NioZipEncoding(cs, useReplacement)
    }

    /**
     * Returns whether a given encoding is UTF-8. If the given name is null, then check the platform's default encoding.
     *
     * @param charsetName If the given name is null, then check the platform's default encoding.
     */
    fun isUTF8(charsetName: String?): Boolean {
        var name = charsetName
        if (name == null) {
            // check platform's default encoding
            name = Charset.defaultCharset().name()
        }

        if (StandardCharsets.UTF_8.name().equals(name, ignoreCase = true))
            return true

        for (alias in StandardCharsets.UTF_8.aliases()) {
            if (alias.equals(name, ignoreCase = true))
                return true
        }

        return false
    }

    fun growBufferBy(buffer: ByteBuffer, increment: Int): ByteBuffer {
        buffer.limit(buffer.position())
        buffer.rewind()
        val on = ByteBuffer.allocate(buffer.capacity() + increment)
        on.put(buffer)
        return on
    }
}
