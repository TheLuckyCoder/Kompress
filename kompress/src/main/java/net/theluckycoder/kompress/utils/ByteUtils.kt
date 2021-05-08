package net.theluckycoder.kompress.utils

/**
 * Utility methods for reading and writing bytes.
 */
internal object ByteUtils {

    /**
     * Reads the given byte array as a little endian long.
     * @param bytes the byte array to convert
     * @param off the offset into the array that starts the value
     * @param length the number of bytes representing the value
     * @return the number read
     * @throws IllegalArgumentException if len is bigger than eight
     */
    fun fromLittleEndian(bytes: ByteArray, off: Int, length: Int): Long {
        checkReadLength(length)
        var l = 0L
        for (i in 0 until length) {
            l = l or (bytes[off + i].toLong() and 0xffL shl 8 * i)
        }
        return l
    }

    /**
     * Inserts the given value into the array as a little endian
     * sequence of the given length starting at the given offset.
     * @param b the array to write into
     * @param value the value to insert
     * @param off the offset into the array that receives the first byte
     * @param length the number of bytes to use to represent the value
     */
    fun toLittleEndian(b: ByteArray, value: Long, off: Int, length: Int) {
        var num = value
        for (i in 0 until length) {
            b[off + i] = (num and 0xff).toByte()
            num = num shr 8
        }
    }

    private fun checkReadLength(length: Int) {
        require(length <= 8) { "Can't read more than eight bytes into a long value" }
    }
}
