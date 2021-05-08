package net.theluckycoder.kompress.zip

import java.util.*

/**
 * NTFS extra field that was designed to store various attributes but
 * in reality only stores timestamps.
 *
 * <pre>
 * 4.5.5 -NTFS Extra Field (0x000a):
 *
 * The following is the layout of the NTFS attributes
 * "extra" block. (Note: At this time the Mtime, Atime
 * and Ctime values MAY be used on any WIN32 system.)
 *
 * Note: all fields stored in Intel low-byte/high-byte order.
 *
 * Value      Size       Description
 * -----      ----       -----------
 * (NTFS)  0x000a     2 bytes    Tag for this "extra" block type
 * TSize      2 bytes    Size of the total "extra" block
 * Reserved   4 bytes    Reserved for future use
 * Tag1       2 bytes    NTFS attribute tag value #1
 * Size1      2 bytes    Size of attribute #1, in bytes
 * (var)      Size1      Attribute #1 data
 * .
 * .
 * .
 * TagN       2 bytes    NTFS attribute tag value #N
 * SizeN      2 bytes    Size of attribute #N, in bytes
 * (var)      SizeN      Attribute #N data
 *
 * For NTFS, values for Tag1 through TagN are as follows:
 * (currently only one set of attributes is defined for NTFS)
 *
 * Tag        Size       Description
 * -----      ----       -----------
 * 0x0001     2 bytes    Tag for attribute #1
 * Size1      2 bytes    Size of attribute #1, in bytes
 * Mtime      8 bytes    File last modification time
 * Atime      8 bytes    File last access time
 * Ctime      8 bytes    File creation time
</pre> *
 *
 * @NotThreadSafe
 */
internal class X000A_NTFS : ZipExtraField {

    private var modifyTime = ZipEightByteInteger.ZERO
    private var accessTime = ZipEightByteInteger.ZERO
    private var createTime = ZipEightByteInteger.ZERO

    override val headerId: ZipShort = HEADER_ID

    /**
     * Length of the extra field in the local file data - without
     * Header-ID or length specifier.
     *
     * @return a `ZipShort` for the length of the data of this extra field
     */
    override fun getLocalFileDataLength(): ZipShort {
        return ZipShort(
            4 /* reserved */
                    + 2 /* Tag#1 */
                    + 2 /* Size#1 */
                    + 3 * 8 /* time values */
        )
    }

    /**
     * Length of the extra field in the local file data - without
     * Header-ID or length specifier.
     *
     *
     * For X5455 the central length is often smaller than the
     * local length, because central cannot contain access or create
     * timestamps.
     *
     * @return a `ZipShort` for the length of the data of this extra field
     */
    override fun getCentralDirectoryLength(): ZipShort = getLocalFileDataLength()

    /**
     * The actual data to put into local file data - without Header-ID
     * or length specifier.
     *
     * @return get the data
     */
    override fun getLocalFileDataData(): ByteArray {
        val data = ByteArray(getLocalFileDataLength().value)
        var pos = 4
        TIME_ATTR_TAG.bytes.copyInto(data, destinationOffset = pos)
        pos += 2
        TIME_ATTR_SIZE.bytes.copyInto(data, destinationOffset = pos)
        pos += 2
        modifyTime.bytes.copyInto(data, destinationOffset = pos)
        pos += 8
        accessTime.bytes.copyInto(data, destinationOffset = pos)
        pos += 8
        createTime.bytes.copyInto(data, destinationOffset = pos)
        return data
    }

    /**
     * The actual data to put into central directory data - without Header-ID
     * or length specifier.
     *
     * @return the central directory data
     */
    override fun getCentralDirectoryData(): ByteArray = getLocalFileDataData()

    /**
     * Populate data from this array as if it was in local file data.
     *
     * @param buffer an array of bytes
     * @param offset the start offset
     * @param length the number of bytes in the array from offset
     */
    override fun parseFromLocalFileData(buffer: ByteArray, offset: Int, length: Int) {
        var newOffset = offset
        val len = newOffset + length

        // skip reserved
        newOffset += 4
        while (newOffset + 4 <= len) {
            val tag = ZipShort(buffer, newOffset)
            newOffset += 2
            if (tag == TIME_ATTR_TAG) {
                readTimeAttr(buffer, newOffset, len - newOffset)
                break
            }
            val (value) = ZipShort(buffer, newOffset)
            newOffset += 2 + value
        }
    }

    /**
     * Doesn't do anything special since this class always uses the
     * same parsing logic for both central directory and local file data.
     */
    override fun parseFromCentralDirectoryData(buffer: ByteArray, offset: Int, length: Int) {
        reset()
        parseFromLocalFileData(buffer, offset, length)
    }

    /**
     * Returns the "File last modification time" of this zip entry as
     * a ZipEightByteInteger object, or [ZipEightByteInteger.ZERO] if no such timestamp exists in the
     * zip entry.
     *
     * @return File last modification time
     */
    fun getModifyTime(): ZipEightByteInteger = modifyTime

    /**
     * Returns the "File last access time" of this zip entry as a
     * ZipEightByteInteger object, or [ZipEightByteInteger.ZERO]
     * if no such timestamp exists in the zip entry.
     *
     * @return File last access time
     */
    fun getAccessTime(): ZipEightByteInteger = accessTime

    /**
     * Returns the "File creation time" of this zip entry as a
     * ZipEightByteInteger object, or [ZipEightByteInteger.ZERO]
     * if no such timestamp exists in the zip entry.
     *
     * @return File creation time
     */
    fun getCreateTime(): ZipEightByteInteger = createTime

    var modifyJavaTime: Date?
        /**
         * Returns the modify time as a java.util.Date
         * of this zip entry, or null if no such timestamp exists in the zip entry.
         *
         * @return modify time as java.util.Date or null.
         */
        get() = zipToDate(modifyTime)
        /**
         * Sets the modify time as a java.util.Date of this zip entry.
         *
         * @param date modify time as java.util.Date
         */
        set(date) {
            setModifyTime(dateToZip(date))
        }

    var accessJavaTime: Date?
        /**
         * Returns the access time as a java.util.Date
         * of this zip entry, or null if no such timestamp exists in the zip entry.
         *
         * @return access time as java.util.Date or null.
         */
        get() = zipToDate(accessTime)
        /**
         * Sets the access time as a java.util.Date
         * of this zip entry.
         *
         * @param d access time as java.util.Date
         */
        set(d) {
            setAccessTime(dateToZip(d))
        }


    var createJavaTime: Date?
        /**
         * Returns the create time as a a java.util.Date of this zip
         * entry, or null if no such timestamp exists in the zip entry.
         *
         * @return create time as java.util.Date or null.
         */
        get() = zipToDate(createTime)
        /**
         * Sets the create time as a java.util.Date
         * of this zip entry.  Supplied value is truncated to per-second
         * precision (milliseconds zeroed-out).
         *
         *
         * Note: the setters for flags and timestamps are decoupled.
         * Even if the timestamp is not-null, it will only be written
         * out if the corresponding bit in the flags is also set.
         *
         *
         * @param date create time as java.util.Date
         */
        set(date) {
            setCreateTime(dateToZip(date))
        }

    /**
     * Sets the File last modification time of this zip entry using a
     * ZipEightByteInteger object.
     *
     * @param t ZipEightByteInteger of the modify time
     */
    fun setModifyTime(t: ZipEightByteInteger?) {
        modifyTime = t ?: ZipEightByteInteger.ZERO
    }

    /**
     * Sets the File last access time of this zip entry using a
     * ZipEightByteInteger object.
     *
     * @param t ZipEightByteInteger of the access time
     */
    fun setAccessTime(t: ZipEightByteInteger?) {
        accessTime = t ?: ZipEightByteInteger.ZERO
    }

    /**
     * Sets the File creation time of this zip entry using a
     * ZipEightByteInteger object.
     *
     * @param t ZipEightByteInteger of the create time
     */
    fun setCreateTime(t: ZipEightByteInteger?) {
        createTime = t ?: ZipEightByteInteger.ZERO
    }

    /**
     * Returns a String representation of this class useful for
     * debugging purposes.
     *
     * @return A String representation of this class useful for
     * debugging purposes.
     */
    override fun toString(): String {
        return "0x000A Zip Extra Field:" +
                " Modify:[" + modifyJavaTime + "] " +
                " Access:[" + accessJavaTime + "] " +
                " Create:[" + createJavaTime + "] "
    }

    override fun equals(other: Any?): Boolean {
        if (other is X000A_NTFS) {
            return modifyTime == other.modifyTime &&
                    accessTime == other.accessTime &&
                    createTime == other.createTime
        }
        return false
    }

    override fun hashCode(): Int {
        var hc = -123
        hc = hc xor modifyTime.hashCode()
        // Since accessTime is often same as modifyTime,
        // this prevents them from XOR negating each other.
        hc = hc xor Integer.rotateLeft(accessTime.hashCode(), 11)
        hc = hc xor Integer.rotateLeft(createTime.hashCode(), 22)
        return hc
    }

    /**
     * Reset state back to newly constructed state.  Helps us make sure
     * parse() calls always generate clean results.
     */
    private fun reset() {
        modifyTime = ZipEightByteInteger.ZERO
        accessTime = ZipEightByteInteger.ZERO
        createTime = ZipEightByteInteger.ZERO
    }

    private fun readTimeAttr(data: ByteArray, offset: Int, length: Int) {
        var newOffset = offset
        if (length >= 2 + 3 * 8) {
            val tagValueLength = ZipShort(data, newOffset)
            if (TIME_ATTR_SIZE == tagValueLength) {
                newOffset += 2
                modifyTime = ZipEightByteInteger(data, newOffset)
                newOffset += 8
                accessTime = ZipEightByteInteger(data, newOffset)
                newOffset += 8
                createTime = ZipEightByteInteger(data, newOffset)
            }
        }
    }

    companion object {
        /**
         * The Header-ID.
         */
        val HEADER_ID = ZipShort(0x000a)

        private val TIME_ATTR_TAG = ZipShort(0x0001)
        private val TIME_ATTR_SIZE = ZipShort(3 * 8)

        // https://msdn.microsoft.com/en-us/library/windows/desktop/ms724290%28v=vs.85%29.aspx
        // A file time is a 64-bit value that represents the number of
        // 100-nanosecond intervals that have elapsed since 12:00
        // A.M. January 1, 1601 Coordinated Universal Time (UTC).
        // this is the offset of Windows time 0 to Unix epoch in 100-nanosecond intervals
        private const val EPOCH_OFFSET = -116444736000000000L
        private fun dateToZip(date: Date?): ZipEightByteInteger? {
            return if (date != null) ZipEightByteInteger(date.time * 10000L - EPOCH_OFFSET) else null
        }

        private fun zipToDate(z: ZipEightByteInteger?): Date? {
            if (z == null || ZipEightByteInteger.ZERO == z) {
                return null
            }
            val l = (z.longValue + EPOCH_OFFSET) / 10000L
            return Date(l)
        }
    }
}