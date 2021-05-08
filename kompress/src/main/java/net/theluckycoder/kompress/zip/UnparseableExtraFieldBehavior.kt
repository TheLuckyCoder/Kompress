package net.theluckycoder.kompress.zip

import java.util.zip.ZipException

/**
 * Handles extra field data that doesn't follow the recommended
 * pattern for extra fields with a two-byte key and a two-byte length.
 */
public interface UnparseableExtraFieldBehavior {
    /**
     * Decides what to do with extra field data that doesn't follow the recommended pattern.
     *
     * @param data the array of extra field data
     * @param offset offset into data where the unparseable data starts
     * @param length the length of unparseable data
     * @param local whether the extra field data stems from the local
     * file header. If this is false then the data is part if the
     * central directory header extra data.
     * @param claimedLength length of the extra field claimed by the
     * third and forth byte if it did follow the recommended pattern
     *
     * @return null if the data should be ignored or an extra field
     * implementation that represents the data
     * @throws ZipException if an error occurs or unparseable extra
     * fields must not be accepted
     */
    @Throws(ZipException::class)
    public fun onUnparseableExtraField(
        data: ByteArray, offset: Int, length: Int, local: Boolean, claimedLength: Int
    ): ZipExtraField?
}
