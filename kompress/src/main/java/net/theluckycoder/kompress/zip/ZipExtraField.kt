package net.theluckycoder.kompress.zip

import java.util.zip.ZipException

/**
 * General format of extra field data.
 *
 *
 * Extra fields usually appear twice per file, once in the local
 * file data and once in the central directory.  Usually they are the
 * same, but they don't have to be. ZipOutputStream will
 * only use the local file data in both places.
 *
 */
public interface ZipExtraField {
    /**
     * The Header-ID.
     *
     * @return The HeaderId value
     */
    public val headerId: ZipShort

    /**
     * Length of the extra field in the local file data - without Header-ID or length specifier.
     * @return the length of the field in the local file data
     */
    public fun getLocalFileDataLength(): ZipShort

    /**
     * Length of the extra field in the central directory - without Header-ID or length specifier.
     * @return the length of the field in the central directory
     */
    public fun getCentralDirectoryLength(): ZipShort

    /**
     * The actual data to put into local file data - without Header-ID or length specifier.
     * @return the data
     */
    public fun getLocalFileDataData(): ByteArray?

    /**
     * The actual data to put into central directory - without Header-ID or
     * length specifier.
     * @return the data
     */
    public fun getCentralDirectoryData(): ByteArray?

    /**
     * Populate data from this array as if it was in local file data.
     *
     * @param buffer the buffer to read data from
     * @param offset offset into buffer to read data
     * @param length the length of data
     * @throws ZipException on error
     */
    @Throws(ZipException::class)
    public fun parseFromLocalFileData(buffer: ByteArray, offset: Int, length: Int)

    /**
     * Populate data from this array as if it was in central directory data.
     *
     * @param buffer the buffer to read data from
     * @param offset offset into buffer to read data
     * @param length the length of data
     * @throws ZipException on error
     */
    @Throws(ZipException::class)
    public fun parseFromCentralDirectoryData(buffer: ByteArray, offset: Int, length: Int)

    public companion object {
        /**
         * Size of an extra field field header (id + length).
         */
        public const val EXTRAFIELD_HEADER_SIZE: Int = 4
    }
}
