package net.theluckycoder.kompress.zip

import java.util.zip.ZipException

/**
 * If this extra field is added as the very first extra field of the
 * archive, Solaris will consider it an executable jar file.
 * @Immutable
 */
internal class JarMarker : ZipExtraField {

    override val headerId: ZipShort = HEADER_ID

    /**
     * Length of the extra field in the local file data - without
     * Header-ID or length specifier.
     * @return 0
     */
    override fun getLocalFileDataLength(): ZipShort = NULL

    /**
     * Length of the extra field in the central directory - without
     * Header-ID or length specifier.
     * @return 0
     */
    override fun getCentralDirectoryLength(): ZipShort = NULL

    /**
     * The actual data to put into local file data - without Header-ID
     * or length specifier.
     * @return the data
     */
    override fun getLocalFileDataData(): ByteArray = NO_BYTES

    /**
     * The actual data to put central directory - without Header-ID or
     * length specifier.
     * @return the data
     */
    override fun getCentralDirectoryData(): ByteArray = NO_BYTES

    /**
     * Populate data from this array as if it was in local file data.
     * @param buffer an array of bytes
     * @param offset the start offset
     * @param length the number of bytes in the array from offset
     *
     * @throws ZipException on error
     */
    @Throws(ZipException::class)
    override fun parseFromLocalFileData(buffer: ByteArray, offset: Int, length: Int) {
        if (length != 0)
            throw ZipException("JarMarker doesn't expect any data")
    }

    /**
     * Doesn't do anything special since this class always uses the
     * same data in central directory and local file data.
     */
    override fun parseFromCentralDirectoryData(buffer: ByteArray, offset: Int, length: Int) {
        parseFromLocalFileData(buffer, offset, length)
    }

    companion object {
        /**
         * The Header-ID.
         */
        val HEADER_ID = ZipShort(0xCAFE)
        private val NULL = ZipShort(0)
        private val NO_BYTES = ByteArray(0)

        /**
         * Since JarMarker is stateless we can always use the same instance.
         * @return the DEFAULT jarmaker.
         */
        val instance = JarMarker()
    }
}
