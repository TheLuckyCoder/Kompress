package net.theluckycoder.kompress.zip

/**
 * Circular byte buffer.
 *
 * @author Emmanuel Bourg
 */
internal class CircularBuffer(
    private val size: Int
) {

    private val buffer: ByteArray = ByteArray(size)

    /** Index of the next data to be read from the buffer  */
    private var readIndex = 0

    /** Index of the next data written in the buffer  */
    private var writeIndex = 0

    /**
     * Tells if a new byte can be read from the buffer.
     */
    fun available(): Boolean = readIndex != writeIndex

    /**
     * Writes a byte to the buffer.
     */
    fun put(value: Int) {
        buffer[writeIndex] = value.toByte()
        writeIndex = (writeIndex + 1) % size
    }

    fun put(value: Byte) = put(value.toInt())

    /**
     * Reads a byte from the buffer.
     */
    fun get(): Int {
        if (available()) {
            val value = buffer[readIndex].toInt()
            readIndex = (readIndex + 1) % size
            return value and 0xFF
        }
        return -1
    }

    /**
     * Copy a previous interval in the buffer to the current position.
     *
     * @param distance the distance from the current write position
     * @param length   the number of bytes to copy
     */
    fun copy(distance: Int, length: Int) {
        val pos1 = writeIndex - distance
        val pos2 = pos1 + length
        for (i in pos1 until pos2) {
            buffer[writeIndex] = buffer[(i + size) % size]
            writeIndex = (writeIndex + 1) % size
        }
    }
}
