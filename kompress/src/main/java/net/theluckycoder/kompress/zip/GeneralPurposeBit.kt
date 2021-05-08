package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.zip.ZipShort.Utils.getValue
import net.theluckycoder.kompress.zip.ZipShort.Utils.putShort

/**
 * Parser/encoder for the "general purpose bit" field in ZIP's local
 * file and central directory headers.
 *
 * @NotThreadSafe
 */
public class GeneralPurposeBit(
    private var languageEncodingFlag: Boolean = false,
    private var dataDescriptorFlag: Boolean = false,
    private var encryptionFlag: Boolean = false,
    private var strongEncryptionFlag: Boolean = false,
) : Cloneable {

    /**
     * Returns the sliding dictionary size used by the compression method 6 (imploding).
     */
    internal var slidingDictionarySize: Int = 0
        private set

    /**
     * Returns the number of trees used by the compression method 6 (imploding).
     */
    public var numberOfShannonFanoTrees: Int = 0
        private set

    /**
     * whether the current entry uses UTF8 for file name and comment.
     * @return whether the current entry uses UTF8 for file name and comment.
     */
    public fun usesUTF8ForNames(): Boolean = languageEncodingFlag

    /**
     * whether the current entry will use UTF8 for file name and comment.
     * @param bool whether the current entry will use UTF8 for file name and comment.
     */
    public fun useUTF8ForNames(bool: Boolean) {
        languageEncodingFlag = bool
    }

    /**
     * whether the current entry uses the data descriptor to store CRC
     * and size information.
     * @return whether the current entry uses the data descriptor to store CRC
     * and size information
     */
    public fun usesDataDescriptor(): Boolean = dataDescriptorFlag

    /**
     * whether the current entry will use the data descriptor to store
     * CRC and size information.
     * @param bool whether the current entry will use the data descriptor to store
     * CRC and size information
     */
    public fun useDataDescriptor(bool: Boolean) {
        dataDescriptorFlag = bool
    }

    /**
     * whether the current entry is encrypted.
     * @return whether the current entry is encrypted
     */
    public fun usesEncryption(): Boolean = encryptionFlag

    /**
     * whether the current entry will be encrypted.
     * @param bool whether the current entry will be encrypted
     */
    public fun useEncryption(bool: Boolean) {
        encryptionFlag = bool
    }

    /**
     * whether the current entry is encrypted using strong encryption.
     * @return whether the current entry is encrypted using strong encryption
     */
    public fun usesStrongEncryption(): Boolean = encryptionFlag && strongEncryptionFlag

    /**
     * whether the current entry will be encrypted  using strong encryption.
     * @param bool whether the current entry will be encrypted  using strong encryption
     */
    public fun useStrongEncryption(bool: Boolean) {
        strongEncryptionFlag = bool
        if (bool) useEncryption(true)
    }

    /**
     * Encodes the set bits in a form suitable for ZIP archives.
     * @return the encoded general purpose bits
     */
    public fun encode(): ByteArray {
        val result = ByteArray(2)
        encode(result, 0)
        return result
    }

    /**
     * Encodes the set bits in a form suitable for ZIP archives.
     *
     * @param buffer the output buffer
     * @param  offset
     * The offset within the output buffer of the first byte to be written.
     * must be non-negative and no larger than <tt>buf.length-2</tt>
     */
    public fun encode(buffer: ByteArray, offset: Int) {
        putShort(
            value = (if (dataDescriptorFlag) DATA_DESCRIPTOR_FLAG else 0)
                    or (if (languageEncodingFlag) UFT8_NAMES_FLAG else 0)
                    or (if (encryptionFlag) ENCRYPTION_FLAG else 0)
                    or (if (strongEncryptionFlag) STRONG_ENCRYPTION_FLAG else 0),
            buffer = buffer, offset = offset
        )
    }

    public override fun clone(): Any = super.clone()

    public companion object {
        /**
         * Indicates that the file is encrypted.
         */
        private const val ENCRYPTION_FLAG = 1 shl 0

        /**
         * Indicates the size of the sliding dictionary used by the compression method 6 (imploding).
         *
         *  * 0: 4096 bytes
         *  * 1: 8192 bytes
         */
        private const val SLIDING_DICTIONARY_SIZE_FLAG = 1 shl 1

        /**
         * Indicates the number of Shannon-Fano trees used by the compression method 6 (imploding).
         *
         *  * 0: 2 trees (lengths, distances)
         *  * 1: 3 trees (literals, lengths, distances)
         */
        private const val NUMBER_OF_SHANNON_FANO_TREES_FLAG = 1 shl 2

        /**
         * Indicates that a data descriptor stored after the file contents
         * will hold CRC and size information.
         */
        private const val DATA_DESCRIPTOR_FLAG = 1 shl 3

        /**
         * Indicates strong encryption.
         */
        private const val STRONG_ENCRYPTION_FLAG = 1 shl 6

        /**
         * Indicates that file names are written in UTF-8.
         */
        private const val UFT8_NAMES_FLAG = 1 shl 11

        /**
         * Parses the supported flags from the given archive data.
         *
         * @param data local file header or a central directory entry.
         * @param offset offset at which the general purpose bit starts
         * @return parsed flags
         */
        @JvmStatic
        public fun parse(data: ByteArray, offset: Int): GeneralPurposeBit {
            val generalPurposeFlag = getValue(data, offset)
            return GeneralPurposeBit().apply {
                useDataDescriptor(generalPurposeFlag and DATA_DESCRIPTOR_FLAG != 0)
                useUTF8ForNames(generalPurposeFlag and UFT8_NAMES_FLAG != 0)
                useStrongEncryption(generalPurposeFlag and STRONG_ENCRYPTION_FLAG != 0)
                useEncryption(generalPurposeFlag and ENCRYPTION_FLAG != 0)
                slidingDictionarySize = if (generalPurposeFlag and SLIDING_DICTIONARY_SIZE_FLAG != 0) 8192 else 4096
                numberOfShannonFanoTrees = if (generalPurposeFlag and NUMBER_OF_SHANNON_FANO_TREES_FLAG != 0) 3 else 2
            }
        }
    }
}