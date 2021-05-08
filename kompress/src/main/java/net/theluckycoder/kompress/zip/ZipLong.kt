package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.utils.ByteUtils.fromLittleEndian
import net.theluckycoder.kompress.utils.ByteUtils.toLittleEndian
import net.theluckycoder.kompress.zip.ZipConstants.WORD
import java.io.Serializable

/**
 * Utility class that represents a four byte integer with conversion
 * rules for the little endian byte order of ZIP files.
 */
public data class ZipLong(val value: Long) : Cloneable, Serializable {

    /**
     * create instance from a int.
     * @param value the int to store as a ZipLong
     */
    public constructor(value: Int) : this(value.toLong())

    /**
     * Create instance from the four bytes starting at offset.
     * @param bytes the bytes to store as a ZipLong
     * @param offset the offset to start
     */
    @JvmOverloads
    public constructor(bytes: ByteArray, offset: Int = 0) : this(getValue(bytes, offset))

    /**
     * Get value as four bytes in big endian byte order.
     * @return value as four bytes in big endian order
     */
    val bytes: ByteArray
        get() = getBytes(value)

    /**
     * Get value as a (signed) java int
     * @return value as int
     */
    val intValue: Int
        get() = value.toInt()

    public fun putLong(buf: ByteArray, offset: Int) {
        putLong(value, buf, offset)
    }

    // TODO Is [Cloneable] actually needed here?
    public override fun clone(): Any = super.clone()

    public companion object Utils {
        private const val serialVersionUID = 1L

        /** Central File Header Signature  */
        @JvmField
        public val CFH_SIG: ZipLong = ZipLong(0X02014B50L)

        /** Local File Header Signature  */
        @JvmField
        public val LFH_SIG: ZipLong = ZipLong(0X04034B50L)

        /**
         * Data Descriptor signature.
         *
         *
         * Actually, PKWARE uses this as marker for split/spanned
         * archives and other archivers have started to use it as Data
         * Descriptor signature (as well).
         */
        @JvmField
        public val DD_SIG: ZipLong = ZipLong(0X08074B50L)

        /**
         * Value stored in size and similar fields if ZIP64 extensions are
         * used.
         */
        @JvmField
        public val ZIP64_MAGIC: ZipLong = ZipLong(ZipConstants.ZIP64_MAGIC)

        /**
         * Marks ZIP archives that were supposed to be split or spanned
         * but only needed a single segment in then end (so are actually
         * neither split nor spanned).
         *
         *
         * This is the "PK00" prefix found in some archives.
         */
        @JvmField
        public val SINGLE_SEGMENT_SPLIT_MARKER: ZipLong = ZipLong(0X30304B50L)

        /**
         * Archive extra data record signature.
         */
        @JvmField
        public val AED_SIG: ZipLong = ZipLong(0X08064B50L)

        /**
         * Get value as four bytes in big endian byte order.
         * @param value the value to convert
         * @return value as four bytes in big endian byte order
         */
        @JvmStatic
        public fun getBytes(value: Long): ByteArray {
            val result = ByteArray(WORD)
            putLong(value, result, 0)
            return result
        }

        /**
         * put the value as four bytes in big endian byte order.
         * @param value the Java long to convert to bytes
         * @param buf the output buffer
         * @param  offset
         * The offset within the output buffer of the first byte to be written.
         * must be non-negative and no larger than <tt>buf.length-4</tt>
         */
        @JvmStatic
        public fun putLong(value: Long, buf: ByteArray, offset: Int) {
            toLittleEndian(buf, value, offset, 4)
        }

        /**
         * Helper method to get the value as a Java long from four bytes starting at given array offset
         * @param bytes the array of bytes
         * @param offset the offset to start
         * @return the corresponding Java long value
         */
        @JvmOverloads
        @JvmStatic
        public fun getValue(bytes: ByteArray, offset: Int = 0): Long = fromLittleEndian(bytes, offset, 4)
    }
}
