package net.theluckycoder.kompress.zip

import java.io.IOException
import java.nio.ByteBuffer

/**
 * An interface for encoders that do a pretty encoding of ZIP
 * file names.
 *
 *
 * There are mostly two implementations, one that uses java.nio
 * [java.nio.charset.Charset] and one implementation,
 * which copes with simple 8 bit charsets, because java-1.4 did not
 * support Cp437 in java.nio.
 *
 *
 * The main reason for defining an own encoding layer comes from
 * the problems with [String.toByteArray], which encodes unknown characters as ASCII
 * quotation marks ('?'). Quotation marks are per definition an
 * invalid file name on some operating systems  like Windows, which
 * leads to ignored ZIP entries.
 *
 *
 * All implementations should implement this interface in a
 * reentrant way.
 */
public interface ZipEncoding {
    /**
     * Check, whether the given string may be losslessly encoded using this
     * encoding.
     *
     * @param name A file name or ZIP comment.
     * @return Whether the given name may be encoded with out any losses.
     */
    public fun canEncode(name: String?): Boolean

    /**
     * Encode a file name or a comment to a byte array suitable for
     * storing it to a serialized zip entry.
     *
     *
     * Examples for CP 437 (in pseudo-notation, right hand side is
     * C-style notation):
     * <pre>
     * encode("\u20AC_for_Dollar.txt") = "%U20AC_for_Dollar.txt"
     * encode("\u00D6lf\u00E4sser.txt") = "\231lf\204sser.txt"
    </pre> *
     *
     * @param name A file name or ZIP comment.
     * @return A byte buffer with a backing array containing the
     * encoded name.  Unmappable characters or malformed
     * character sequences are mapped to a sequence of utf-16
     * words encoded in the format `%Uxxxx`.  It is
     * assumed, that the byte buffer is positioned at the
     * beginning of the encoded result, the byte buffer has a
     * backing array and the limit of the byte buffer points
     * to the end of the encoded result.
     * @throws IOException on error
     */
    @Throws(IOException::class)
    public fun encode(name: String): ByteBuffer

    /**
     * @param data The byte values to decode.
     * @return The decoded string.
     * @throws IOException on error
     */
    @Throws(IOException::class)
    public fun decode(data: ByteArray): String
}