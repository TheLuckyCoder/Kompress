package net.theluckycoder.kompress.zip

/**
 * Wrapper for extra field data that doesn't conform to the recommended format of header-tag + size + data.
 *
 *
 * The header-id is artificial (and not listed as a known ID in [APPNOTE.TXT](https://www.pkware.com/documents/casestudies/APPNOTE.TXT)).  Since it isn't used anywhere
 * except to satisfy the ZipExtraField contract it shouldn't matter anyway.
 *
 * @NotThreadSafe
 */
public class UnparseableExtraFieldData : ZipExtraField {
    private var localFileData: ByteArray? = null
    private var centralDirectoryData: ByteArray? = null

    override val headerId: ZipShort = HEADER_ID

    /**
     * Length of the complete extra field in the local file data.
     *
     * @return The LocalFileDataLength value
     */
    override fun getLocalFileDataLength(): ZipShort =
        ZipShort(localFileData?.run { size } ?: 0)

    /**
     * Length of the complete extra field in the central directory.
     *
     * @return The CentralDirectoryLength value
     */
    override fun getCentralDirectoryLength(): ZipShort =
        centralDirectoryData?.run { ZipShort(size) } ?: getLocalFileDataLength()

    /**
     * The actual data to put into local file data.
     *
     * @return The LocalFileDataData value
     */
    override fun getLocalFileDataData(): ByteArray? = localFileData?.copyOf()

    /**
     * The actual data to put into central directory.
     *
     * @return The CentralDirectoryData value
     */
    override fun getCentralDirectoryData(): ByteArray? =
        if (centralDirectoryData == null) getLocalFileDataData() else centralDirectoryData?.copyOf()

    /**
     * Populate data from this array as if it was in local file data.
     *
     * @param buffer the buffer to read data from
     * @param offset offset into buffer to read data
     * @param length the length of data
     */
    override fun parseFromLocalFileData(buffer: ByteArray, offset: Int, length: Int) {
        localFileData = buffer.copyOfRange(offset, offset + length)
    }

    /**
     * Populate data from this array as if it was in central directory data.
     *
     * @param buffer the buffer to read data from
     * @param offset offset into buffer to read data
     * @param length the length of data
     */
    override fun parseFromCentralDirectoryData(
        buffer: ByteArray, offset: Int, length: Int
    ) {
        centralDirectoryData = buffer.copyOfRange(offset, offset + length)
        if (localFileData == null)
            parseFromLocalFileData(buffer, offset, length)
    }

    public companion object {
        /**
         * The Header-ID.
         *
         * @return a completely arbitrary value that should be ignored.
         */
        public val HEADER_ID: ZipShort = ZipShort(0xACC1)
    }
}
