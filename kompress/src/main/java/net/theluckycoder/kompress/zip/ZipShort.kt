package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.utils.ByteUtils.fromLittleEndian
import net.theluckycoder.kompress.utils.ByteUtils.toLittleEndian
import java.io.Serializable

/**
 * Utility class that represents a two byte integer with conversion
 * rules for the little endian byte order of ZIP files.
 */
public data class ZipShort(val value: Int) : Cloneable, Serializable {

    /**
     * Create instance from the two bytes starting at offset.
     * @param bytes the bytes to store as a ZipShort
     * @param offset the offset to start
     */
    @JvmOverloads
    public constructor(bytes: ByteArray, offset: Int = 0) : this(getValue(bytes, offset))

    /**
     * Get value as two bytes in big endian byte order.
     * @return the value as a a two byte array in big endian byte order
     */
    val bytes: ByteArray
        get() {
            val result = ByteArray(2)
            toLittleEndian(result, value.toLong(), 0, 2)
            return result
        }

    public override fun clone(): Any = super.clone()

    public companion object Utils {
        private const val serialVersionUID = 1L

        /**
         * Get value as two bytes in big endian byte order.
         * @param value the Java int to convert to bytes
         * @return the converted int as a byte array in big endian byte order
         */
        @JvmStatic
        public fun getBytes(value: Int): ByteArray {
            val result = ByteArray(2)
            putShort(value, result, 0)
            return result
        }

        /**
         * put the value as two bytes in big endian byte order.
         * @param value the Java int to convert to bytes
         * @param buffer the output buffer
         * @param  offset
         * The offset within the output buffer of the first byte to be written.
         * must be non-negative and no larger than <tt>buf.length-2</tt>
         */
        @JvmStatic
        public fun putShort(value: Int, buffer: ByteArray, offset: Int) {
            toLittleEndian(buffer, value.toLong(), offset, 2)
        }

        /**
         * Helper method to get the value as a java int from two bytes starting at given array offset
         * @param bytes the array of bytes
         * @param offset the offset to start
         * @return the corresponding java int value
         */
        @JvmStatic
        public fun getValue(bytes: ByteArray, offset: Int): Int = fromLittleEndian(bytes, offset, 2).toInt()

        /**
         * Helper method to get the value as a java int from a two-byte array
         * @param bytes the array of bytes
         * @return the corresponding java int value
         */
        @JvmStatic
        public fun getValue(bytes: ByteArray): Int = getValue(bytes, 0)
    }
}