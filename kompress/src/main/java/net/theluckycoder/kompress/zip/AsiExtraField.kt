package net.theluckycoder.kompress.zip

import java.util.zip.CRC32
import java.util.zip.ZipException

/**
 * Adds Unix file permission and UID/GID fields as well as symbolic
 * link handling.
 *
 *
 * This class uses the ASi extra field in the format:
 * <pre>
 * Value         Size            Description
 * -----         ----            -----------
 * (Unix3) 0x756e        Short           tag for this extra block type
 * TSize         Short           total data size for this block
 * CRC           Long            CRC-32 of the remaining data
 * Mode          Short           file permissions
 * SizDev        Long            symlink'd size OR major/minor dev num
 * UID           Short           user ID
 * GID           Short           group ID
 * (var.)        variable        symbolic link file name
</pre> *
 *
 * taken from appnote.iz (Info-ZIP note, 981119) found at [ftp://ftp.uu.net/pub/archiving/zip/doc/](ftp://ftp.uu.net/pub/archiving/zip/doc/)
 *
 *
 * Short is two bytes and Long is four bytes in big endian byte and
 * word order, device numbers are currently not supported.
 *
 * @NotThreadSafe
 *
 *Since the documentation this class is based upon doesn't mention
 * the character encoding of the file name at all, it is assumed that
 * it uses the current platform's default encoding.
 */
internal class AsiExtraField : ZipExtraField, Cloneable {

    /**
     * Standard Unix stat(2) file mode.
     */
    var mode = 0
        private set

    /**
     * User ID.
     */
    var userId = 0

    /**
     * Group ID.
     */
    var groupId = 0

    /**
     * File this entry points to, if it is a symbolic link.
     *
     *
     * empty string - if entry is not a symbolic link.
     */
    private var link = ""

    /**
     * Is this an entry for a directory?
     */
    private var dirFlag = false

    /**
     * Instance used to calculate checksums.
     */
    private var crc = CRC32()

    /**
     * The Header-ID.
     *
     * @return the value for the header id for this ExtraField
     */
    override val headerId: ZipShort = HEADER_ID

    /**
     * Length of the extra field in the local file data - without
     * Header-ID or length specifier.
     *
     * @return a [ZipShort] for the length of the data of this extra field
     */
    override fun getLocalFileDataLength() = ZipShort(
        WORD // CRC
                + 2 // Mode
                + WORD // SizDev
                + 2 // UID
                + 2 // GID
                + linkedFile.toByteArray().size
    )
    // Uses default charset - see class Javadoc

    /**
     * Delegate to local file data.
     *
     * @return the centralDirectory length
     */
    override fun getCentralDirectoryLength(): ZipShort = getLocalFileDataLength()

    /**
     * The actual data to put into local file data - without Header-ID
     * or length specifier.
     *
     * @return get the data
     */
    override fun getLocalFileDataData(): ByteArray {
        // CRC will be added later
        val data = ByteArray(getLocalFileDataLength().value - WORD)
        System.arraycopy(ZipShort.getBytes(mode), 0, data, 0, 2)
        val linkArray = linkedFile.toByteArray()

        ZipLong.getBytes(linkArray.size.toLong()).copyInto(data, destinationOffset = 2)
        ZipShort.getBytes(userId).copyInto(data, destinationOffset = 6)
        ZipShort.getBytes(groupId).copyInto(data, destinationOffset = 8)
        linkArray.copyInto(data, destinationOffset = 10)

        crc.reset()
        crc.update(data)
        val checksum = crc.value

        return ZipLong.getBytes(checksum) + data
    }

    /**
     * Delegate to local file data.
     */
    override fun getCentralDirectoryData(): ByteArray = getLocalFileDataData()

    /**
     * Indicate that this entry is a symbolic link to the given file name.
     *
     * if it is not a symbolic link.
     */
    var linkedFile: String
        get() = link
        set(name) {
            link = name
            mode = getMode(mode)
        }

    /**
     * Is this entry a symbolic link?
     *
     * @return true if this is a symbolic link
     */
    fun isLink(): Boolean = linkedFile.isNotEmpty()

    /**
     * File mode of this file.
     *
     * @param mode the file mode
     */
    fun setMode(mode: Int) {
        this.mode = getMode(mode)
    }

    /**
     * Indicate whether this entry is a directory.
     */
    var isDirectory: Boolean
        get() = dirFlag && !isLink()
        set(dirFlag) {
            this.dirFlag = dirFlag
            mode = getMode(mode)
        }

    /**
     * Populate data from this array as if it was in local file data.
     *
     * @param buffer   an array of bytes
     * @param offset the start offset
     * @param length the number of bytes in the array from offset
     * @throws ZipException on error
     */
    @Throws(ZipException::class)
    override fun parseFromLocalFileData(buffer: ByteArray, offset: Int, length: Int) {
        val givenChecksum = ZipLong.getValue(buffer, offset)
        val tmp = ByteArray(length - WORD)
        System.arraycopy(buffer, offset + WORD, tmp, 0, length - WORD)
        crc.reset()
        crc.update(tmp)
        val realChecksum = crc.value
        if (givenChecksum != realChecksum) {
            throw ZipException(
                "Bad CRC checksum, expected "
                        + java.lang.Long.toHexString(givenChecksum)
                        + " instead of "
                        + java.lang.Long.toHexString(realChecksum)
            )
        }
        val newMode = ZipShort.getValue(tmp, 0)
        // CheckStyle:MagicNumber OFF
        val linkArray = ByteArray(ZipLong.getValue(tmp, 2).toInt())
        userId = ZipShort.getValue(tmp, 6)
        groupId = ZipShort.getValue(tmp, 8)
        link = when {
            linkArray.isEmpty() ->  ""
            linkArray.size > tmp.size - 10 ->
                throw ZipException("Bad symbolic link name length ${linkArray.size} in ASI extra field")
            else -> {
                System.arraycopy(tmp, 10, linkArray, 0, linkArray.size)
                String(linkArray) // Uses default charset - see class Javadoc
            }
        }
        // CheckStyle:MagicNumber ON
        isDirectory = newMode and UnixStat.DIR_FLAG != 0
        setMode(newMode)
    }

    /**
     * Doesn't do anything special since this class always uses the
     * same data in central directory and local file data.
     */
    @Throws(ZipException::class)
    override fun parseFromCentralDirectoryData(buffer: ByteArray, offset: Int, length: Int) {
        parseFromLocalFileData(buffer, offset, length)
    }

    /**
     * Get the file mode for given permissions with the correct file type.
     *
     * @param mode the mode
     * @return the type with the mode
     */
    private fun getMode(mode: Int): Int {
        var type = UnixStat.FILE_FLAG
        when {
            isLink() -> type = UnixStat.LINK_FLAG
            isDirectory -> type = UnixStat.DIR_FLAG
        }
        return type or (mode and UnixStat.PERM_MASK)
    }

    public override fun clone(): Any {
        val cloned = super.clone() as AsiExtraField
        cloned.crc = CRC32()
        return cloned
    }

    companion object {
        private val HEADER_ID = ZipShort(0x756E)
        private const val WORD = 4
    }
}