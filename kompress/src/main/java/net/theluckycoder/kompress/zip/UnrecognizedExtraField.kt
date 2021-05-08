package net.theluckycoder.kompress.zip

/**
 * Simple placeholder for all those extra fields we don't want to deal
 * with.
 *
 *
 * Assumes local file data and central directory entries are
 * identical - unless told the opposite.
 * @NotThreadSafe
 */
internal class UnrecognizedExtraField(
    /**
     * The Header-ID.
     */
    override var headerId: ZipShort
) : ZipExtraField {

    /**
     * Extra field data in local file data - without
     * Header-ID or length specifier.
     */
    private var localData: ByteArray? = null

    /**
     * Set the extra field data in the local file data -
     * without Header-ID or length specifier.
     * @param data the field data to use
     */
    fun setLocalFileDataData(data: ByteArray?) {
        localData = data?.copyOf()
    }

    /**
     * Get the length of the local data.
     * @return the length of the local data
     */
    override fun getLocalFileDataLength(): ZipShort = ZipShort(localData?.size ?: 0)

    /**
     * Get the local data.
     * @return the local data
     */
    override fun getLocalFileDataData(): ByteArray? = localData?.copyOf()

    /**
     * Extra field data in central directory - without
     * Header-ID or length specifier.
     */
    private var centralData: ByteArray? = null

    /**
     * Set the extra field data in central directory.
     * @param data the data to use
     */
    fun setCentralDirectoryData(data: ByteArray?) {
        centralData = data?.copyOf()
    }

    /**
     * Get the central data length.
     * If there is no central data, get the local file data length.
     * @return the central data length
     */
    override fun getCentralDirectoryLength(): ZipShort {
        centralData?.let {
            return ZipShort(it.size)
        }
        return getLocalFileDataLength()
    }

    /**
     * Get the central data.
     * @return the central data if present, else return the local file data
     */
    override fun getCentralDirectoryData(): ByteArray? {
        return centralData?.copyOf() ?: getLocalFileDataData()
    }

    /**
     * @param buffer the array of bytes.
     * @param offset the source location in the data array.
     * @param length the number of bytes to use in the data array.
     * @see [ZipExtraField.parseFromLocalFileData]
     */
    override fun parseFromLocalFileData(buffer: ByteArray, offset: Int, length: Int) {
        setLocalFileDataData(buffer.copyOfRange(offset, offset + length))
    }

    /**
     * @param buffer the array of bytes.
     * @param offset the source location in the data array.
     * @param length the number of bytes to use in the data array.
     * @see [ZipExtraField.parseFromCentralDirectoryData]
     */
    override fun parseFromCentralDirectoryData(buffer: ByteArray, offset: Int, length: Int) {
        val tmp = buffer.copyOfRange(offset, offset + length)
        setCentralDirectoryData(tmp)

        if (localData == null)
            setLocalFileDataData(tmp)
    }
}
