package net.theluckycoder.kompress.zip

import java.util.zip.ZipException

/**
 * Base class for all PKWare strong crypto extra headers.
 *
 *
 * This base class acts as a marker so you know you can ignore all
 * extra fields that extend this class if you are not interested in
 * the meta data of PKWare strong encryption.
 *
 * **Algorithm IDs** - integer identifier of the encryption algorithm from
 * the following range
 *
 *
 *  * 0x6601 - DES
 *  * 0x6602 - RC2 (version needed to extract &lt; 5.2)
 *  * 0x6603 - 3DES 168
 *  * 0x6609 - 3DES 112
 *  * 0x660E - AES 128
 *  * 0x660F - AES 192
 *  * 0x6610 - AES 256
 *  * 0x6702 - RC2 (version needed to extract &gt;= 5.2)
 *  * 0x6720 - Blowfish
 *  * 0x6721 - Twofish
 *  * 0x6801 - RC4
 *  * 0xFFFF - Unknown algorithm
 *
 *
 * **Hash Algorithms** - integer identifier of the hash algorithm from the
 * following range
 *
 *
 *  * 0x0000 - none
 *  * 0x0001 - CRC32
 *  * 0x8003 - MD5
 *  * 0x8004 - SHA1
 *  * 0x8007 - RIPEMD160
 *  * 0x800C - SHA256
 *  * 0x800D - SHA384
 *  * 0x800E - SHA512
 *
 */
public abstract class PKWareExtraHeader protected constructor(
    /**
     * Get the header id.
     */
    override val headerId: ZipShort
) : ZipExtraField {

    /**
     * Extra field data in local file data - without Header-ID or length
     * specifier.
     */
    private var localData: ByteArray? = null

    /**
     * Extra field data in central directory - without Header-ID or length
     * specifier.
     */
    private var centralData: ByteArray? = null


    /**
     * Set the extra field data in the local file data - without Header-ID or
     * length specifier.
     *
     * @param data
     * the field data to use
     */
    public fun setLocalFileDataData(data: ByteArray?) {
        localData = data?.copyOf()
    }

    /**
     * Get the length of the local data.
     *
     * @return the length of the local data
     */
    override fun getLocalFileDataLength(): ZipShort = ZipShort(localData?.size ?: 0)

    /**
     * Get the local data.
     *
     * @return the local data
     */
    override fun getLocalFileDataData(): ByteArray? = localData?.copyOf()

    /**
     * Set the extra field data in central directory.
     *
     * @param data
     * the data to use
     */
    public fun setCentralDirectoryData(data: ByteArray?) {
        centralData = data?.copyOf()
    }

    /**
     * Get the central data length. If there is no central data, get the local
     * file data length.
     *
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
     *
     * @return the central data if present, else return the local file data
     */
    override fun getCentralDirectoryData(): ByteArray? {
        return centralData?.copyOf() ?: getLocalFileDataData()
    }

    /**
     * @param buffer the array of bytes.
     * @param offset the source location in the data array.
     * @param length the number of bytes to use in the data array.
     * @see ZipExtraField.parseFromLocalFileData
     */
    @Throws(ZipException::class)
    override fun parseFromLocalFileData(buffer: ByteArray, offset: Int, length: Int) {
        setLocalFileDataData(buffer.copyOfRange(offset, offset + length))
    }

    /**
     * @param buffer the array of bytes.
     * @param offset the source location in the data array.
     * @param length the number of bytes to use in the data array.
     * @see ZipExtraField.parseFromCentralDirectoryData
     */
    @Throws(ZipException::class)
    override fun parseFromCentralDirectoryData(buffer: ByteArray, offset: Int, length: Int) {
        val tmp = buffer.copyOfRange(offset, offset + length)
        setCentralDirectoryData(tmp)
        if (localData == null) {
            setLocalFileDataData(tmp)
        }
    }

    @Throws(ZipException::class)
    protected fun assertMinimalLength(minimum: Int, length: Int) {
        if (length < minimum)
            throw ZipException("${javaClass.name} is too short, only $length bytes, expected at least $minimum")
    }

    /**
     * Encryption algorithm.
     */
    @Suppress("unused")
    public enum class EncryptionAlgorithm(
        /**
         * the algorithm id.
         */
        public val code: Int
    ) {
        DES(0x6601), RC2pre52(0x6602), TripleDES168(0x6603),
        TripleDES192(0x6609), AES128(0x660E), AES192(0x660F),
        AES256(0x6610), RC2(0x6702), RC4(0x6801), UNKNOWN(0xFFFF);

        public companion object {
            private val codeToEnum: Map<Int, EncryptionAlgorithm> =
                values().map { it.code to it }.toMap()

            /**
             * Returns the EncryptionAlgorithm for the given code or null if the
             * method is not known.
             * @param code the code of the algorithm
             * @return the EncryptionAlgorithm for the given code or null
             * if the method is not known
             */
            @JvmStatic
            public fun getAlgorithmByCode(code: Int): EncryptionAlgorithm? = codeToEnum[code]
        }
    }

    /**
     * Hash Algorithm
     */
    @Suppress("unused")
    public enum class HashAlgorithm(
        /**
         * the hash algorithm ID.
         */
        public val code: Int
    ) {
        NONE(0), CRC32(1), MD5(0x8003),
        SHA1(0x8004), RIPEND160(0x8007), SHA256(0x800C),
        SHA384(0x800D), SHA512(0x800E);

        public companion object {
            private val codeToEnum: Map<Int, HashAlgorithm> = values().map { it.code to it }.toMap()

            /**
             * Returns the HashAlgorithm for the given code or null if the method is
             * not known.
             * @param code the code of the algorithm
             * @return the HashAlgorithm for the given code or null
             * if the method is not known
             */
            @JvmStatic
            public fun getAlgorithmByCode(code: Int): HashAlgorithm? = codeToEnum[code]
        }
    }
}
