package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.zip.ZipConstants.DWORD
import net.theluckycoder.kompress.zip.ZipConstants.WORD
import java.util.zip.ZipException

/**
 * Holds size and other extended information for entries that use Zip64
 * features.
 *
 *
 * Currently Commons Compress doesn't support encrypting the
 * central directory so the note in APPNOTE.TXT about masking doesn't
 * apply.
 *
 *
 * The implementation relies on data being read from the local file
 * header and assumes that both size values are always present.
 *
 * @see [PKWARE
 * APPNOTE.TXT, section 4.5.3](https://www.pkware.com/documents/casestudies/APPNOTE.TXT)
 *
 *
 * @NotThreadSafe
 */
internal class Zip64ExtendedInformationExtraField : ZipExtraField {

    /**
     * The uncompressed size stored in this extra field.
     */
    var size: ZipEightByteInteger? = null

    /**
     * The uncompressed size stored in this extra field.
     */
    var compressedSize: ZipEightByteInteger? = null

    /**
     * The relative header offset stored in this extra field.
     */
    var relativeHeaderOffset: ZipEightByteInteger? = null

    /**
     * The disk start number stored in this extra field.
     */
    var diskStartNumber: ZipLong? = null

    /**
     * Stored in [parseFromCentralDirectoryData] so it can be reused when ZipFile
     * calls [reparseCentralDirectoryData].
     *
     *
     * Not used for anything else
     */
    private var rawCentralDirectoryData: ByteArray? = null

    override val headerId: ZipShort = HEADER_ID

    /**
     * This constructor should only be used by the code that reads archives
     */
    constructor()

    /**
     * Creates an extra field based on all four possible values.
     *
     * @param size the entry's original size
     * @param compressedSize the entry's compressed size
     * @param relativeHeaderOffset the entry's offset
     * @param diskStart the disk start
     */
    @JvmOverloads
    constructor(
        size: ZipEightByteInteger,
        compressedSize: ZipEightByteInteger,
        relativeHeaderOffset: ZipEightByteInteger? = null,
        diskStart: ZipLong? = null
    ) {
        this.size = size
        this.compressedSize = compressedSize
        this.relativeHeaderOffset = relativeHeaderOffset
        diskStartNumber = diskStart
    }

    override fun getLocalFileDataLength() = ZipShort(if (size != null) 2 * DWORD else 0)

    override fun getCentralDirectoryLength(): ZipShort {
        return ZipShort(
            (if (size != null) DWORD else 0)
                    + (if (compressedSize != null) DWORD else 0)
                    + (if (relativeHeaderOffset != null) DWORD else 0)
                    + if (diskStartNumber != null) WORD else 0
        )
    }

    override fun getLocalFileDataData(): ByteArray {
        if (size != null || compressedSize != null) {
            require(!(size == null || compressedSize == null)) { LFH_MUST_HAVE_BOTH_SIZES_MSG }
            val data = ByteArray(2 * DWORD)
            addSizes(data)
            return data
        }
        return EMPTY
    }

    override fun getCentralDirectoryData(): ByteArray {
        val data = ByteArray(getCentralDirectoryLength().value)
        var offset = addSizes(data)

        relativeHeaderOffset?.let {
            it.bytes.copyInto(data, destinationOffset = offset)
            offset += DWORD
        }

        diskStartNumber?.let {
            it.bytes.copyInto(data, destinationOffset = offset)
            offset += WORD
        }

        return data
    }

    @Throws(ZipException::class)
    override fun parseFromLocalFileData(buffer: ByteArray, offset: Int, length: Int) {
        var newOffset = offset
        if (length == 0) {
            // no local file data at all, may happen if an archive
            // only holds a ZIP64 extended information extra field
            // inside the central directory but not inside the local
            // file header
            return
        }
        if (length < 2 * DWORD) {
            throw ZipException(LFH_MUST_HAVE_BOTH_SIZES_MSG)
        }
        size = ZipEightByteInteger(buffer, newOffset)
        newOffset += DWORD
        compressedSize = ZipEightByteInteger(buffer, newOffset)
        newOffset += DWORD
        var remaining: Int = length - 2 * DWORD
        if (remaining >= DWORD) {
            relativeHeaderOffset = ZipEightByteInteger(buffer, newOffset)
            newOffset += DWORD
            remaining -= DWORD
        }
        if (remaining >= WORD) {
            diskStartNumber = ZipLong(buffer, newOffset)
            newOffset += WORD // assignment as documentation
            remaining -= WORD // assignment as documentation
        }
    }

    @Throws(ZipException::class)
    override fun parseFromCentralDirectoryData(
        buffer: ByteArray, offset: Int,
        length: Int
    ) {
        // store for processing in reparseCentralDirectoryData
        var newOffset = offset
        rawCentralDirectoryData = buffer.copyOfRange(newOffset, length)

        // if there is no size information in here, we are screwed and
        // can only hope things will get resolved by LFH data later
        // But there are some cases that can be detected
        // * all data is there
        // * length == 24 -> both sizes and offset
        // * length % 8 == 4 -> at least we can identify the diskStart field
        when {
            length >= 3 * DWORD + WORD -> parseFromLocalFileData(buffer, newOffset, length)
            length == 3 * DWORD -> {
                size = ZipEightByteInteger(buffer, newOffset)
                newOffset += DWORD
                compressedSize = ZipEightByteInteger(buffer, newOffset)
                newOffset += DWORD
                relativeHeaderOffset = ZipEightByteInteger(buffer, newOffset)
            }
            length % DWORD == WORD -> diskStartNumber = ZipLong(buffer, newOffset + length - WORD)
        }
    }

    /**
     * Parses the raw bytes read from the central directory extra
     * field with knowledge which fields are expected to be there.
     *
     *
     * All four fields inside the zip64 extended information extra
     * field are optional and must only be present if their corresponding
     * entry inside the central directory contains the correct magic
     * value.
     *
     * @param hasUncompressedSize flag to read from central directory
     * @param hasCompressedSize flag to read from central directory
     * @param hasRelativeHeaderOffset flag to read from central directory
     * @param hasDiskStart flag to read from central directory
     * @throws ZipException on error
     */
    @Throws(ZipException::class)
    fun reparseCentralDirectoryData(
        hasUncompressedSize: Boolean,
        hasCompressedSize: Boolean,
        hasRelativeHeaderOffset: Boolean,
        hasDiskStart: Boolean
    ) {
        rawCentralDirectoryData?.let { rawCentralDirectoryData ->
            val expectedLength: Int = ((if (hasUncompressedSize) DWORD else 0)
                    + (if (hasCompressedSize) DWORD else 0)
                    + (if (hasRelativeHeaderOffset) DWORD else 0)
                    + if (hasDiskStart) WORD else 0)
            if (rawCentralDirectoryData.size < expectedLength) {
                throw ZipException(
                    "Central directory zip64 extended"
                            + " information extra field's length"
                            + " doesn't match central directory"
                            + " data.  Expected length "
                            + expectedLength + " but is "
                            + rawCentralDirectoryData.size
                )
            }
            var offset = 0
            if (hasUncompressedSize) {
                size = ZipEightByteInteger(rawCentralDirectoryData, offset)
                offset += DWORD
            }
            if (hasCompressedSize) {
                compressedSize = ZipEightByteInteger(
                    rawCentralDirectoryData,
                    offset
                )
                offset += DWORD
            }
            if (hasRelativeHeaderOffset) {
                relativeHeaderOffset = ZipEightByteInteger(rawCentralDirectoryData, offset)
                offset += DWORD
            }
            if (hasDiskStart) {
                diskStartNumber = ZipLong(rawCentralDirectoryData, offset)
                offset += WORD // assignment as documentation
            }
        }
    }

    private fun addSizes(data: ByteArray): Int {
        var off = 0
        if (size != null) {
            System.arraycopy(size!!.bytes, 0, data, 0, DWORD)
            off += DWORD
        }
        if (compressedSize != null) {
            System.arraycopy(compressedSize!!.bytes, 0, data, off, DWORD)
            off += DWORD
        }
        return off
    }

    companion object {
        internal val HEADER_ID = ZipShort(0x0001)

        private const val LFH_MUST_HAVE_BOTH_SIZES_MSG = ("Zip64 extended information must contain"
                + " both size values in the local file header.")
        private val EMPTY = ByteArray(0)
    }
}