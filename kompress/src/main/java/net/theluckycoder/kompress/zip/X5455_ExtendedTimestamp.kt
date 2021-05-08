package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.zip.ZipUtil.unsignedIntToSignedByte
import java.io.Serializable
import java.util.*
import java.util.zip.ZipException
import kotlin.experimental.and
import kotlin.experimental.inv
import kotlin.experimental.or

/**
 *
 * An extra field that stores additional file and directory timestamp data
 * for zip entries.   Each zip entry can include up to three timestamps
 * (modify, access, create*).  The timestamps are stored as 32 bit signed
 * integers representing seconds since UNIX epoch (Jan 1st, 1970, UTC).
 * This field improves on zip's default timestamp granularity, since it
 * allows one to store additional timestamps, and, in addition, the timestamps
 * are stored using per-second granularity (zip's default behaviour can only store
 * timestamps to the nearest *even* second).
 *
 *
 * Unfortunately, 32 (signed) bits can only store dates up to the year 2037,
 * and so this extra field will eventually be obsolete.  Enjoy it while it lasts!
 *
 *
 *  * **modifyTime:**
 * most recent time of file/directory modification
 * (or file/dir creation if the entry has not been
 * modified since it was created).
 *
 *  * **accessTime:**
 * most recent time file/directory was opened
 * (e.g., read from disk).  Many people disable
 * their operating systems from updating this value
 * using the NOATIME mount option to optimize disk behaviour,
 * and thus it's not always reliable.  In those cases
 * it's always equal to modifyTime.
 *
 *  * ***createTime:**
 * modern linux file systems (e.g., ext2 and newer)
 * do not appear to store a value like this, and so
 * it's usually omitted altogether in the zip extra
 * field.  Perhaps other unix systems track this.
 *
 *
 *
 * We're using the field definition given in Info-Zip's source archive:
 * zip-3.0.tar.gz/proginfo/extrafld.txt
 *
 * <pre>
 * Value         Size        Description
 * -----         ----        -----------
 * 0x5455        Short       tag for this extra block type ("UT")
 * TSize         Short       total data size for this block
 * Flags         Byte        info bits
 * (ModTime)     Long        time of last modification (UTC/GMT)
 * (AcTime)      Long        time of last access (UTC/GMT)
 * (CrTime)      Long        time of original creation (UTC/GMT)
 *
 * Central-header version:
 *
 * Value         Size        Description
 * -----         ----        -----------
 * 0x5455        Short       tag for this extra block type ("UT")
 * TSize         Short       total data size for this block
 * Flags         Byte        info bits (refers to local header!)
 * (ModTime)     Long        time of last modification (UTC/GMT)
</pre> *
 */
internal class X5455_ExtendedTimestamp : ZipExtraField, Cloneable, Serializable {

    // The 3 boolean fields (below) come from this flags byte.  The remaining 5 bits
    // are ignored according to the current version of the spec (December 2012).
    private var flags: Byte = 0

    /**
     * Returns whether bit0 of the flags byte is set or not,
     * which should correspond to the presence or absence of
     * a modify timestamp in this particular zip entry.
     *
     * @return true if bit0 of the flags byte is set.
     */
    // Note: even if bit1 and bit2 are set, the Central data will still not contain
    // access/create fields:  only local data ever holds those!  This causes
    // some of our implementation to look a little odd, with seemingly spurious
    // != null and length checks.
    var isBit0_modifyTimePresent = false
        private set

    /**
     * Returns whether bit1 of the flags byte is set or not,
     * which should correspond to the presence or absence of
     * a "last access" timestamp in this particular zip entry.
     *
     * @return true if bit1 of the flags byte is set.
     */
    var isBit1_accessTimePresent = false
        private set

    /**
     * Returns whether bit2 of the flags byte is set or not,
     * which should correspond to the presence or absence of
     * a create timestamp in this particular zip entry.
     *
     * @return true if bit2 of the flags byte is set.
     */
    var isBit2_createTimePresent = false
        private set

    private var modifyTime: ZipLong? = null
    private var accessTime: ZipLong? = null
    private var createTime: ZipLong? = null

    override val headerId: ZipShort = HEADER_ID

    /**
     * Length of the extra field in the local file data - without
     * Header-ID or length specifier.
     *
     * @return a `ZipShort` for the length of the data of this extra field
     */
    override fun getLocalFileDataLength(): ZipShort {
        return ZipShort(
            1 +
                    (if (isBit0_modifyTimePresent) 4 else 0) +
                    (if (isBit1_accessTimePresent && accessTime != null) 4 else 0) +
                    if (isBit2_createTimePresent && createTime != null) 4 else 0
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
    override fun getCentralDirectoryLength(): ZipShort {
        return ZipShort(
            1 + if (isBit0_modifyTimePresent) 4 else 0
        )
    }

    /**
     * The actual data to put into local file data - without Header-ID
     * or length specifier.
     *
     * @return get the data
     */
    override fun getLocalFileDataData(): ByteArray {
        val data = ByteArray(getLocalFileDataLength().value)
        var pos = 0
        data[pos++] = 0
        if (isBit0_modifyTimePresent) {
            data[0] = data[0] or MODIFY_TIME_BIT
            System.arraycopy(modifyTime!!.bytes, 0, data, pos, 4)
            pos += 4
        }

        if (isBit1_accessTimePresent && accessTime != null) {
            data[0] = data[0] or ACCESS_TIME_BIT
            System.arraycopy(accessTime!!.bytes, 0, data, pos, 4)
            pos += 4
        }

        if (isBit2_createTimePresent && createTime != null) {
            data[0] = data[0] or CREATE_TIME_BIT
            System.arraycopy(createTime!!.bytes, 0, data, pos, 4)
            pos += 4 // assignment as documentation
        }

        return data
    }

    /**
     * The actual data to put into central directory data - without Header-ID
     * or length specifier.
     *
     * @return the central directory data
     */
    override fun getCentralDirectoryData(): ByteArray {
        // Truncate out create & access time (last 8 bytes) from
        // the copy of the local data we obtained:
        return getLocalFileDataData().copyOf(getCentralDirectoryLength().value)
    }

    /**
     * Populate data from this array as if it was in local file data.
     *
     * @param buffer an array of bytes
     * @param offset the start offset
     * @param length the number of bytes in the array from offset
     * @throws ZipException on error
     */
    @Throws(ZipException::class)
    override fun parseFromLocalFileData(buffer: ByteArray, offset: Int, length: Int) {
        var newOffset = offset
        reset()
        if (length < 1)
            throw ZipException("X5455_ExtendedTimestamp too short, only $length bytes")

        val len = newOffset + length
        setFlags(buffer[newOffset++])
        if (isBit0_modifyTimePresent && newOffset + 4 <= len) {
            modifyTime = ZipLong(buffer, newOffset)
            newOffset += 4
        }
        if (isBit1_accessTimePresent && newOffset + 4 <= len) {
            accessTime = ZipLong(buffer, newOffset)
            newOffset += 4
        }
        if (isBit2_createTimePresent && newOffset + 4 <= len) {
            createTime = ZipLong(buffer, newOffset)
            newOffset += 4 // assignment as documentation
        }
    }

    /**
     * Doesn't do anything special since this class always uses the
     * same parsing logic for both central directory and local file data.
     */
    @Throws(ZipException::class)
    override fun parseFromCentralDirectoryData(buffer: ByteArray, offset: Int, length: Int) {
        reset()
        parseFromLocalFileData(buffer, offset, length)
    }

    /**
     * Reset state back to newly constructed state.  Helps us make sure
     * parse() calls always generate clean results.
     */
    private fun reset() {
        setFlags(0.toByte())
        modifyTime = null
        accessTime = null
        createTime = null
    }

    /**
     * Sets flags byte.  The flags byte tells us which of the
     * three datestamp fields are present in the data:
     * <pre>
     * bit0 - modify time
     * bit1 - access time
     * bit2 - create time
    </pre> *
     * Only first 3 bits of flags are used according to the
     * latest version of the spec (December 2012).
     *
     * @param flags flags byte indicating which of the
     * three datestamp fields are present.
     */
    fun setFlags(flags: Byte) {
        this.flags = flags
        isBit0_modifyTimePresent = flags and MODIFY_TIME_BIT == MODIFY_TIME_BIT
        isBit1_accessTimePresent = flags and ACCESS_TIME_BIT == ACCESS_TIME_BIT
        isBit2_createTimePresent = flags and CREATE_TIME_BIT == CREATE_TIME_BIT
    }

    /**
     * Gets flags byte.  The flags byte tells us which of the
     * three datestamp fields are present in the data:
     * <pre>
     * bit0 - modify time
     * bit1 - access time
     * bit2 - create time
    </pre> *
     * Only first 3 bits of flags are used according to the
     * latest version of the spec (December 2012).
     *
     * @return flags byte indicating which of the
     * three datestamp fields are present.
     */
    fun getFlags(): Byte = flags

    /**
     * Returns the modify time (seconds since epoch) of this zip entry
     * as a ZipLong object, or null if no such timestamp exists in the
     * zip entry.
     *
     * @return modify time (seconds since epoch) or null.
     */
    fun getModifyTime(): ZipLong? = modifyTime

    /**
     * Returns the access time (seconds since epoch) of this zip entry
     * as a ZipLong object, or null if no such timestamp exists in the
     * zip entry.
     *
     * @return access time (seconds since epoch) or null.
     */
    fun getAccessTime(): ZipLong? = accessTime

    /**
     *
     *
     * Returns the create time (seconds since epoch) of this zip entry
     * as a ZipLong object, or null if no such timestamp exists in the
     * zip entry.
     *
     *
     * Note: modern linux file systems (e.g., ext2)
     * do not appear to store a "create time" value, and so
     * it's usually omitted altogether in the zip extra
     * field.  Perhaps other unix systems track this.
     *
     * @return create time (seconds since epoch) or null.
     */
    fun getCreateTime(): ZipLong? = createTime

    var modifyJavaTime: Date?
        /**
         * Returns the modify time as a java.util.Date
         * of this zip entry, or null if no such timestamp exists in the zip entry.
         * The milliseconds are always zeroed out, since the underlying data
         * offers only per-second precision.
         *
         * @return modify time as java.util.Date or null.
         */
        get() = zipLongToDate(modifyTime)
        /**
         * Sets the modify time as a java.util.Date
         * of this zip entry.  Supplied value is truncated to per-second
         * precision (milliseconds zeroed-out).
         *
         *
         * Note: the setters for flags and timestamps are decoupled.
         * Even if the timestamp is not-null, it will only be written
         * out if the corresponding bit in the flags is also set.
         *
         * @param d modify time as java.util.Date
         */
        set(d) {
            setModifyTime(dateToZipLong(d))
        }

    var accessJavaTime: Date?
        /**
         * Returns the access time as a java.util.Date
         * of this zip entry, or null if no such timestamp exists in the zip entry.
         * The milliseconds are always zeroed out, since the underlying data
         * offers only per-second precision.
         *
         * @return access time as java.util.Date or null.
         */
        get() = zipLongToDate(accessTime)
        /**
         * Sets the access time as a java.util.Date
         * of this zip entry.  Supplied value is truncated to per-second
         * precision (milliseconds zeroed-out).
         *
         *
         * Note: the setters for flags and timestamps are decoupled.
         * Even if the timestamp is not-null, it will only be written
         * out if the corresponding bit in the flags is also set.
         *
         * @param d access time as java.util.Date
         */
        set(d) {
            setAccessTime(dateToZipLong(d))
        }

    var createJavaTime: Date?
        /**
         *
         *
         * Returns the create time as a a java.util.Date
         * of this zip entry, or null if no such timestamp exists in the zip entry.
         * The milliseconds are always zeroed out, since the underlying data
         * offers only per-second precision.
         *
         *
         * Note: modern linux file systems (e.g., ext2)
         * do not appear to store a "create time" value, and so
         * it's usually omitted altogether in the zip extra
         * field.  Perhaps other unix systems track this.
         *
         * @return create time as java.util.Date or null.
         */
        get() = zipLongToDate(createTime)
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
         * @param value create time as java.util.Date
         */
        set(value) {
            setCreateTime(dateToZipLong(value))
        }

    /**
     *
     *
     * Sets the modify time (seconds since epoch) of this zip entry
     * using a ZipLong object.
     *
     *
     * Note: the setters for flags and timestamps are decoupled.
     * Even if the timestamp is not-null, it will only be written
     * out if the corresponding bit in the flags is also set.
     *
     *
     * @param l ZipLong of the modify time (seconds per epoch)
     */
    fun setModifyTime(l: ZipLong?) {
        isBit0_modifyTimePresent = l != null
        flags = (if (l != null) flags or MODIFY_TIME_BIT else flags and MODIFY_TIME_BIT.inv())
        modifyTime = l
    }

    /**
     * Sets the access time (seconds since epoch) of this zip entry
     * using a ZipLong object
     *
     *
     * Note: the setters for flags and timestamps are decoupled.
     * Even if the timestamp is not-null, it will only be written
     * out if the corresponding bit in the flags is also set.
     *
     *
     * @param l ZipLong of the access time (seconds per epoch)
     */
    fun setAccessTime(l: ZipLong?) {
        isBit1_accessTimePresent = l != null
        flags = (if (l != null) flags or ACCESS_TIME_BIT else flags and ACCESS_TIME_BIT.inv())
        accessTime = l
    }

    /**
     * Sets the create time (seconds since epoch) of this zip entry
     * using a ZipLong object
     *
     *
     * Note: the setters for flags and timestamps are decoupled.
     * Even if the timestamp is not-null, it will only be written
     * out if the corresponding bit in the flags is also set.
     *
     *
     * @param long ZipLong of the create time (seconds per epoch)
     */
    fun setCreateTime(long: ZipLong?) {
        isBit2_createTimePresent = long != null
        flags = if (long != null) flags or CREATE_TIME_BIT else flags and CREATE_TIME_BIT.inv()
        createTime = long
    }

    /**
     * Returns a String representation of this class useful for
     * debugging purposes.
     *
     * @return A String representation of this class useful for
     * debugging purposes.
     */
    override fun toString(): String {
        val builder = StringBuilder()
        builder.append("0x5455 Zip Extra Field: Flags=")
        builder.append(Integer.toBinaryString(unsignedIntToSignedByte(flags.toInt()).toInt()))
            .append(' ')
        if (isBit0_modifyTimePresent && modifyTime != null)
            builder.append(" Modify:[").append(modifyJavaTime).append("] ")

        if (isBit1_accessTimePresent && accessTime != null)
            builder.append(" Access:[").append(accessJavaTime).append("] ")

        if (isBit2_createTimePresent && createTime != null)
            builder.append(" Create:[").append(createJavaTime).append("] ")

        return builder.toString()
    }

    @Throws(CloneNotSupportedException::class)
    public override fun clone(): Any = super.clone()

    override fun equals(other: Any?): Boolean {
        if (other is X5455_ExtendedTimestamp) {

            // The ZipLong==ZipLong clauses handle the cases where both are null.
            // and only last 3 bits of flags matter.
            return flags and 0x07 == other.flags and 0x07 &&
                    modifyTime == other.modifyTime &&
                    accessTime == other.accessTime &&
                    createTime == other.createTime
        }
        return false
    }

    override fun hashCode(): Int {
        var hc: Int = -123 * (flags and 0x07) // only last 3 bits of flags matter
        if (modifyTime != null) {
            hc = hc xor modifyTime.hashCode()
        }
        if (accessTime != null) {
            // Since accessTime is often same as modifyTime,
            // this prevents them from XOR negating each other.
            hc = hc xor Integer.rotateLeft(accessTime.hashCode(), 11)
        }
        if (createTime != null) {
            hc = hc xor Integer.rotateLeft(createTime.hashCode(), 22)
        }
        return hc
    }

    companion object {
        private const val serialVersionUID = 1L

        /**
         * The Header-ID.
         */
        val HEADER_ID = ZipShort(0x5455)

        /**
         * The bit set inside the flags by when the last modification time
         * is present in this extra field.
         */
        const val MODIFY_TIME_BIT: Byte = 1

        /**
         * The bit set inside the flags by when the lasr access time is
         * present in this extra field.
         */
        const val ACCESS_TIME_BIT: Byte = 2

        /**
         * The bit set inside the flags by when the original creation time
         * is present in this extra field.
         */
        const val CREATE_TIME_BIT: Byte = 4

        /**
         * Utility method converts java.util.Date (milliseconds since epoch)
         * into a ZipLong (seconds since epoch).
         *
         *
         * Also makes sure the converted ZipLong is not too big to fit
         * in 32 unsigned bits.
         *
         * @param date java.util.Date to convert to ZipLong
         * @return ZipLong
         */
        private fun dateToZipLong(date: Date?): ZipLong? {
            date?.let {
                return unixTimeToZipLong(date.time / 1000)
            }
            return null
        }

        private fun zipLongToDate(unixTime: ZipLong?): Date? {
            return if (unixTime != null) Date(unixTime.intValue * 1000L) else null
        }

        private fun unixTimeToZipLong(l: Long): ZipLong {
            require(!(l < Int.MIN_VALUE || l > Int.MAX_VALUE)) { "X5455 timestamps must fit in a signed 32 bit integer: $l" }
            return ZipLong(l)
        }
    }
}
