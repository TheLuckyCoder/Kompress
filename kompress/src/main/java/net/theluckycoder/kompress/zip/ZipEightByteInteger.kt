package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.zip.ZipConstants.BYTE_MASK
import java.io.Serializable
import java.math.BigInteger
import kotlin.experimental.or

/**
 * Utility class that represents an eight byte integer with conversion
 * rules for the little endian byte order of ZIP files.
 * @Immutable
 */
public data class ZipEightByteInteger(val value: BigInteger) : Serializable {

    /**
     * Create instance from a number.
     * @param value the long to store as a ZipEightByteInteger
     */
    public constructor(value: Long) : this(BigInteger.valueOf(value))

    /**
     * Create instance from the eight bytes starting at offset.
     * @param bytes the bytes to store as a ZipEightByteInteger
     * @param offset the offset to start
     */
    @JvmOverloads
    public constructor(bytes: ByteArray, offset: Int = 0) : this(getValue(bytes, offset))

    /**
     * Get value as eight bytes in big endian byte order.
     * @return value as eight bytes in big endian order
     */
    val bytes: ByteArray
        get() = getBytes(value)

    /**
     * Get value as Java long.
     * @return value as a long
     */
    val longValue: Long
        get() = value.toLong()

    public companion object Utils {
        private const val serialVersionUID = 1L

        private const val BYTE_1 = 1
        private const val BYTE_1_MASK = 0xFF00
        private const val BYTE_1_SHIFT = 8
        private const val BYTE_2 = 2
        private const val BYTE_2_MASK = 0xFF0000
        private const val BYTE_2_SHIFT = 16
        private const val BYTE_3 = 3
        private const val BYTE_3_MASK = 0xFF000000L
        private const val BYTE_3_SHIFT = 24
        private const val BYTE_4 = 4
        private const val BYTE_4_MASK = 0xFF00000000L
        private const val BYTE_4_SHIFT = 32
        private const val BYTE_5 = 5
        private const val BYTE_5_MASK = 0xFF0000000000L
        private const val BYTE_5_SHIFT = 40
        private const val BYTE_6 = 6
        private const val BYTE_6_MASK = 0xFF000000000000L
        private const val BYTE_6_SHIFT = 48
        private const val BYTE_7 = 7
        private const val BYTE_7_MASK = 0x7F00000000000000L
        private const val BYTE_7_SHIFT = 56
        private const val LEFTMOST_BIT_SHIFT = 63
        private const val LEFTMOST_BIT = 0x80.toByte()

        @JvmField
        public val ZERO: ZipEightByteInteger = ZipEightByteInteger(0)

        /**
         * Get value as eight bytes in big endian byte order.
         * @param value the value to convert
         * @return value as eight bytes in big endian byte order
         */
        public fun getBytes(value: Long): ByteArray = getBytes(BigInteger.valueOf(value))

        /**
         * Get value as eight bytes in big endian byte order.
         * @param value the value to convert
         * @return value as eight bytes in big endian byte order
         */
        public fun getBytes(value: BigInteger): ByteArray {
            val result = ByteArray(8)
            val lValue = value.toLong()
            result[0] = (lValue and BYTE_MASK.toLong()).toByte()
            result[BYTE_1] = (lValue and BYTE_1_MASK.toLong() shr BYTE_1_SHIFT).toByte()
            result[BYTE_2] = (lValue and BYTE_2_MASK.toLong() shr BYTE_2_SHIFT).toByte()
            result[BYTE_3] = (lValue and BYTE_3_MASK shr BYTE_3_SHIFT).toByte()
            result[BYTE_4] = (lValue and BYTE_4_MASK shr BYTE_4_SHIFT).toByte()
            result[BYTE_5] = (lValue and BYTE_5_MASK shr BYTE_5_SHIFT).toByte()
            result[BYTE_6] = (lValue and BYTE_6_MASK shr BYTE_6_SHIFT).toByte()
            result[BYTE_7] = (lValue and BYTE_7_MASK shr BYTE_7_SHIFT).toByte()

            if (value.testBit(LEFTMOST_BIT_SHIFT))
                result[BYTE_7] = result[BYTE_7] or LEFTMOST_BIT

            return result
        }

        /**
         * Helper method to get the value as a Java long from eight bytes
         * starting at given array offset
         * @param bytes the array of bytes
         * @param offset the offset to start
         * @return the corresponding Java long value
         */
        public fun getLongValue(bytes: ByteArray, offset: Int): Long = getValue(bytes, offset).toLong()

        /**
         * Helper method to get the value as a Java BigInteger from eight
         * bytes starting at given array offset
         * @param bytes the array of bytes
         * @param offset the offset to start
         * @return the corresponding Java BigInteger value
         */
        public fun getValue(bytes: ByteArray, offset: Int): BigInteger {
            var value: Long = bytes[offset + BYTE_7].toLong() shl BYTE_7_SHIFT and BYTE_7_MASK
            value += bytes[offset + BYTE_6].toLong() shl BYTE_6_SHIFT and BYTE_6_MASK
            value += bytes[offset + BYTE_5].toLong() shl BYTE_5_SHIFT and BYTE_5_MASK
            value += bytes[offset + BYTE_4].toLong() shl BYTE_4_SHIFT and BYTE_4_MASK
            value += bytes[offset + BYTE_3].toLong() shl BYTE_3_SHIFT and BYTE_3_MASK
            value += bytes[offset + BYTE_2].toLong() shl BYTE_2_SHIFT and BYTE_2_MASK.toLong()
            value += bytes[offset + BYTE_1].toLong() shl BYTE_1_SHIFT and BYTE_1_MASK.toLong()
            value += bytes[offset].toLong() and BYTE_MASK.toLong()

            val bigValue = BigInteger.valueOf(value)
            return if ((bytes[offset + BYTE_7].toLong() and LEFTMOST_BIT.toLong()) == LEFTMOST_BIT.toLong()) bigValue.setBit(
                LEFTMOST_BIT_SHIFT
            ) else bigValue
        }

        /**
         * Helper method to get the value as a Java long from an eight-byte array
         * @param bytes the array of bytes
         * @return the corresponding Java long value
         */
        public fun getLongValue(bytes: ByteArray): Long = getLongValue(bytes, 0)

        /**
         * Helper method to get the value as a Java long from an eight-byte array
         * @param bytes the array of bytes
         * @return the corresponding Java BigInteger value
         */
        public fun getValue(bytes: ByteArray): BigInteger = getValue(bytes, 0)
    }
}