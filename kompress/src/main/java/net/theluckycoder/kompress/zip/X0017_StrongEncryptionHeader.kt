package net.theluckycoder.kompress.zip

import java.util.zip.ZipException

/**
 * Strong Encryption Header (0x0017).
 *
 *
 * Certificate-based encryption:
 *
 * <pre>
 * Value     Size     Description
 * -----     ----     -----------
 * 0x0017    2 bytes  Tag for this "extra" block type
 * TSize     2 bytes  Size of data that follows
 * Format    2 bytes  Format definition for this record
 * AlgID     2 bytes  Encryption algorithm identifier
 * Bitlen    2 bytes  Bit length of encryption key (32-448 bits)
 * Flags     2 bytes  Processing flags
 * RCount    4 bytes  Number of recipients.
 * HashAlg   2 bytes  Hash algorithm identifier
 * HSize     2 bytes  Hash size
 * SRList    (var)    Simple list of recipients hashed public keys
 *
 * Flags -   This defines the processing flags.
</pre> *
 *
 *
 *  * 0x0007 - reserved for future use
 *  * 0x000F - reserved for future use
 *  * 0x0100 - Indicates non-OAEP key wrapping was used.  If this
 * this field is set, the version needed to extract must
 * be at least 61.  This means OAEP key wrapping is not
 * used when generating a Master Session Key using
 * ErdData.
 *  * 0x4000 - ErdData must be decrypted using 3DES-168, otherwise use the
 * same algorithm used for encrypting the file contents.
 *  * 0x8000 - reserved for future use
 *
 *
 * <pre>
 * RCount - This defines the number intended recipients whose
 * public keys were used for encryption.  This identifies
 * the number of elements in the SRList.
 *
 * see also: reserved1
 *
 * HashAlg - This defines the hash algorithm used to calculate
 * the public key hash of each public key used
 * for encryption. This field currently supports
 * only the following value for SHA-1
 *
 * 0x8004 - SHA1
 *
 * HSize -   This defines the size of a hashed public key.
 *
 * SRList -  This is a variable length list of the hashed
 * public keys for each intended recipient.  Each
 * element in this list is HSize.  The total size of
 * SRList is determined using RCount * HSize.
</pre> *
 *
 *
 * Password-based Extra Field 0x0017 in central header only.
 *
 * <pre>
 * Value     Size     Description
 * -----     ----     -----------
 * 0x0017    2 bytes  Tag for this "extra" block type
 * TSize     2 bytes  Size of data that follows
 * Format    2 bytes  Format definition for this record
 * AlgID     2 bytes  Encryption algorithm identifier
 * Bitlen    2 bytes  Bit length of encryption key (32-448 bits)
 * Flags     2 bytes  Processing flags
 * (more?)
</pre> *
 *
 *
 * **Format** - the data format identifier for this record. The only value
 * allowed at this time is the integer value 2.
 *
 *
 * Password-based Extra Field 0x0017 preceding compressed file data.
 *
 * <pre>
 * Value     Size     Description
 * -----     ----     -----------
 * 0x0017    2 bytes  Tag for this "extra" block type
 * IVSize    2 bytes  Size of initialization vector (IV)
 * IVData    IVSize   Initialization vector for this file
 * Size      4 bytes  Size of remaining decryption header data
 * Format    2 bytes  Format definition for this record
 * AlgID     2 bytes  Encryption algorithm identifier
 * Bitlen    2 bytes  Bit length of encryption key (32-448 bits)
 * Flags     2 bytes  Processing flags
 * ErdSize   2 bytes  Size of Encrypted Random Data
 * ErdData   ErdSize  Encrypted Random Data
 * Reserved1 4 bytes  Reserved certificate processing data
 * Reserved2 (var)    Reserved for certificate processing data
 * VSize     2 bytes  Size of password validation data
 * VData     VSize-4  Password validation data
 * VCRC32    4 bytes  Standard ZIP CRC32 of password validation data
 *
 * IVData - The size of the IV should match the algorithm block size.
 * The IVData can be completely random data.  If the size of
 * the randomly generated data does not match the block size
 * it should be complemented with zero's or truncated as
 * necessary.  If IVSize is 0,then IV = CRC32 + Uncompressed
 * File Size (as a 64 bit little-endian, unsigned integer value).
 *
 * Format -  the data format identifier for this record.  The only
 * value allowed at this time is the integer value 2.
 *
 * ErdData - Encrypted random data is used to store random data that
 * is used to generate a file session key for encrypting
 * each file.  SHA1 is used to calculate hash data used to
 * derive keys.  File session keys are derived from a master
 * session key generated from the user-supplied password.
 * If the Flags field in the decryption header contains
 * the value 0x4000, then the ErdData field must be
 * decrypted using 3DES. If the value 0x4000 is not set,
 * then the ErdData field must be decrypted using AlgId.
 *
 * Reserved1 - Reserved for certificate processing, if value is
 * zero, then Reserved2 data is absent.  See the explanation
 * under the Certificate Processing Method for details on
 * this data structure.
 *
 * Reserved2 - If present, the size of the Reserved2 data structure
 * is located by skipping the first 4 bytes of this field
 * and using the next 2 bytes as the remaining size.  See
 * the explanation under the Certificate Processing Method
 * for details on this data structure.
 *
 * VSize - This size value will always include the 4 bytes of the
 * VCRC32 data and will be greater than 4 bytes.
 *
 * VData - Random data for password validation.  This data is VSize
 * in length and VSize must be a multiple of the encryption
 * block size.  VCRC32 is a checksum value of VData.
 * VData and VCRC32 are stored encrypted and start the
 * stream of encrypted data for a file.
</pre> *
 *
 *
 * Reserved1 - Certificate Decryption Header Reserved1 Data:
 *
 * <pre>
 * Value     Size     Description
 * -----     ----     -----------
 * RCount    4 bytes  Number of recipients.
</pre> *
 *
 *
 * RCount - This defines the number intended recipients whose public keys were
 * used for encryption. This defines the number of elements in the REList field
 * defined below.
 *
 *
 * Reserved2 - Certificate Decryption Header Reserved2 Data Structures:
 *
 * <pre>
 * Value     Size     Description
 * -----     ----     -----------
 * HashAlg   2 bytes  Hash algorithm identifier
 * HSize     2 bytes  Hash size
 * REList    (var)    List of recipient data elements
 *
 * HashAlg - This defines the hash algorithm used to calculate
 * the public key hash of each public key used
 * for encryption. This field currently supports
 * only the following value for SHA-1
 *
 * 0x8004 - SHA1
 *
 * HSize -   This defines the size of a hashed public key
 * defined in REHData.
 *
 * REList -  This is a variable length of list of recipient data.
 * Each element in this list consists of a Recipient
 * Element data structure as follows:
</pre> *
 *
 *
 * Recipient Element (REList) Data Structure:
 *
 * <pre>
 * Value     Size     Description
 * -----     ----     -----------
 * RESize    2 bytes  Size of REHData + REKData
 * REHData   HSize    Hash of recipients public key
 * REKData   (var)    Simple key blob
 *
 *
 * RESize -  This defines the size of an individual REList
 * element.  This value is the combined size of the
 * REHData field + REKData field.  REHData is defined by
 * HSize.  REKData is variable and can be calculated
 * for each REList element using RESize and HSize.
 *
 * REHData - Hashed public key for this recipient.
 *
 * REKData - Simple Key Blob.  The format of this data structure
 * is identical to that defined in the Microsoft
 * CryptoAPI and generated using the CryptExportKey()
 * function.  The version of the Simple Key Blob
 * supported at this time is 0x02 as defined by
 * Microsoft.
 *
 * For more details see https://msdn.microsoft.com/en-us/library/aa920051.aspx
</pre> *
 *
 *
 * **Flags** - Processing flags needed for decryption
 *
 *
 *  * 0x0001 - Password is required to decrypt
 *  * 0x0002 - Certificates only
 *  * 0x0003 - Password or certificate required to decrypt
 *  * 0x0007 - reserved for future use
 *  * 0x000F - reserved for future use
 *  * 0x0100 - indicates non-OAEP key wrapping was used. If this field is set
 * the version needed to extract must be at least 61. This means OAEP key
 * wrapping is not used when generating a Master Session Key using ErdData.
 *  * 0x4000 - ErdData must be decrypted using 3DES-168, otherwise use the same
 * algorithm used for encrypting the file contents.
 *  * 0x8000 - reserved for future use.
 *
 *
 *
 * **See the section describing the Strong Encryption Specification for
 * details. Refer to the section in this document entitled
 * "Incorporating PKWARE Proprietary Technology into Your Product" for more
 * information.**
 *
 * @NotThreadSafe
 */
public class X0017_StrongEncryptionHeader : PKWareExtraHeader(ZipShort(0x0017)) {

    private var format = 0 // TODO written but not read

    /**
     * Encryption algorithm.
     */
    public var encryptionAlgorithm: EncryptionAlgorithm? = null
        private set
    private var bitlen = 0 // TODO written but not read
    private var flags = 0 // TODO written but not read

    /**
     * Get record count.
     * @return the record count
     */
    public var recordCount: Long = 0
        private set

    /**
     * Get hash algorithm.
     * @return the hash algorithm
     */
    public var hashAlgorithm: HashAlgorithm? = null
        private set
    private var hashSize = 0

    // encryption data
    private lateinit var ivData: ByteArray
    private lateinit var erdData: ByteArray

    // encryption key
    private lateinit var recipientKeyHash: ByteArray
    private lateinit var keyBlob: ByteArray

    // password verification data
    private lateinit var vData: ByteArray
    private lateinit var vCRC32: ByteArray

    /**
     * Parse central directory format.
     *
     * @param buffer the buffer to read data from
     * @param offset offset into buffer to read data
     * @param length the length of data
     * @throws ZipException if an error occurs
     */
    @Throws(ZipException::class)
    public fun parseCentralDirectoryFormat(buffer: ByteArray, offset: Int, length: Int) {
        assertMinimalLength(12, length)
        // TODO: double check we really do not want to call super here
        format = ZipShort.getValue(buffer, offset)
        encryptionAlgorithm = EncryptionAlgorithm.getAlgorithmByCode(ZipShort.getValue(buffer, offset + 2))
        bitlen = ZipShort.getValue(buffer, offset + 4)
        flags = ZipShort.getValue(buffer, offset + 6)
        recordCount = ZipLong.getValue(buffer, offset + 8)
        if (recordCount > 0) {
            assertMinimalLength(16, length)
            hashAlgorithm = HashAlgorithm.getAlgorithmByCode(ZipShort.getValue(buffer, offset + 12))
            hashSize = ZipShort.getValue(buffer, offset + 14)
        }
    }

    /**
     * Parse file header format.
     *
     *
     * (Password only?)
     *
     * @param buffer the buffer to read data from
     * @param offset offset into buffer to read data
     * @param length the length of data
     * @throws ZipException if an error occurs
     */
    @Throws(ZipException::class)
    public fun parseFileFormat(buffer: ByteArray, offset: Int, length: Int) {
        assertMinimalLength(4, length)
        val ivSize = ZipShort.getValue(buffer, offset)
        assertDynamicLengthFits("ivSize", ivSize, 4, length)
        assertMinimalLength(offset + 4, ivSize)
        // TODO: what is at offset + 2?
        ivData = buffer.copyOfRange(offset + 4, ivSize)
        assertMinimalLength(16 + ivSize, length) // up to and including erdSize
        // TODO: what is at offset + 4 + ivSize?
        format = ZipShort.getValue(buffer, offset + ivSize + 6)
        encryptionAlgorithm = EncryptionAlgorithm.getAlgorithmByCode(ZipShort.getValue(buffer, offset + ivSize + 8))
        bitlen = ZipShort.getValue(buffer, offset + ivSize + 10)
        flags = ZipShort.getValue(buffer, offset + ivSize + 12)
        val erdSize = ZipShort.getValue(buffer, offset + ivSize + 14)
        assertDynamicLengthFits("erdSize", erdSize, ivSize + 16, length)
        assertMinimalLength(offset + ivSize + 16, erdSize)
        erdData = buffer.copyOfRange(offset + ivSize + 16, erdSize)
        assertMinimalLength(16 + 4 + ivSize + erdSize, length)
        recordCount = ZipLong.getValue(buffer, offset + ivSize + 16 + erdSize)

        if (recordCount == 0L) {
            assertMinimalLength(ivSize + 20 + erdSize + 2, length)
            val vSize = ZipShort.getValue(buffer, offset + ivSize + 20 + erdSize)
            assertDynamicLengthFits("vSize", vSize, ivSize + 22 + erdSize, length)
            if (vSize < 4)
                throw ZipException("Invalid X0017_StrongEncryptionHeader: vSize $vSize is too small to hold CRC")

            assertMinimalLength(offset + ivSize + 22 + erdSize, vSize - 4)
            vData = buffer.copyOfRange(offset + ivSize + 22 + erdSize, vSize - 4)
            assertMinimalLength(offset + ivSize + 22 + erdSize + vSize - 4, 4)
            vCRC32 = buffer.copyOfRange(offset + ivSize + 22 + erdSize + vSize - 4, 4)
        } else {
            assertMinimalLength(ivSize + 20 + erdSize + 6, length) // up to and including resize
            hashAlgorithm = HashAlgorithm.getAlgorithmByCode(ZipShort.getValue(buffer, offset + ivSize + 20 + erdSize))
            hashSize = ZipShort.getValue(buffer, offset + ivSize + 22 + erdSize)
            val resize = ZipShort.getValue(buffer, offset + ivSize + 24 + erdSize)
            recipientKeyHash = ByteArray(hashSize)
            if (resize < hashSize) {
                throw ZipException(
                    "Invalid X0017_StrongEncryptionHeader: resize " + resize
                            + " is too small to hold hashSize" + hashSize
                )
            }
            keyBlob = ByteArray(resize - hashSize)
            // TODO: this looks suspicious, 26 rather than 24 would be "after" resize
            assertDynamicLengthFits("resize", resize, ivSize + 24 + erdSize, length)
            // TODO use Arrays.copyOfRange
            System.arraycopy(buffer, offset + ivSize + 24 + erdSize, recipientKeyHash, 0, hashSize)
            System.arraycopy(buffer, offset + ivSize + 24 + erdSize + hashSize, keyBlob, 0, resize - hashSize)
            assertMinimalLength(ivSize + 26 + erdSize + resize + 2, length)

            val vSize = ZipShort.getValue(buffer, offset + ivSize + 26 + erdSize + resize)
            if (vSize < 4)
                throw ZipException("Invalid X0017_StrongEncryptionHeader: vSize $vSize is too small to hold CRC")

            // TODO: these offsets look even more suspicious, the constant should likely be 28 rather than 22
            assertDynamicLengthFits("vSize", vSize, ivSize + 22 + erdSize + resize, length)

            vData = buffer.copyOfRange(offset + ivSize + 22 + erdSize + resize, vSize - 4)
            val fromIndex = offset + ivSize + 22 + erdSize + resize + vSize - 4
            vCRC32 = buffer.copyOfRange(fromIndex, fromIndex + 4)
        }

        // validate values?
    }

    @Throws(ZipException::class)
    override fun parseFromLocalFileData(buffer: ByteArray, offset: Int, length: Int) {
        super.parseFromLocalFileData(buffer, offset, length)
        parseFileFormat(buffer, offset, length)
    }

    @Throws(ZipException::class)
    override fun parseFromCentralDirectoryData(buffer: ByteArray, offset: Int, length: Int) {
        super.parseFromCentralDirectoryData(buffer, offset, length)
        parseCentralDirectoryFormat(buffer, offset, length)
    }

    @Throws(ZipException::class)
    private fun assertDynamicLengthFits(
        what: String, dynamicLength: Int, prefixLength: Int,
        length: Int
    ) {
        if (prefixLength + dynamicLength > length) {
            throw ZipException(
                "Invalid X0017_StrongEncryptionHeader: $what $dynamicLength doesn't fit into $length bytes of data at position $prefixLength"
            )
        }
    }
}