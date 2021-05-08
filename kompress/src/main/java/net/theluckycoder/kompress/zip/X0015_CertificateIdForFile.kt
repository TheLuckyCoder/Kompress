package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.zip.ZipShort.Utils.getValue
import java.util.zip.ZipException

/**
 * X.509 Certificate ID and Signature for individual file (0x0015).
 *
 *
 * This field contains the information about which certificate in the PKCS#7
 * store was used to sign a particular file. It also contains the signature
 * data. This field can appear multiple times, but can only appear once per
 * certificate.
 *
 *
 * Note: all fields stored in Intel low-byte/high-byte order.
 *
 * <pre>
 * Value     Size     Description
 * -----     ----     -----------
 * (CID)   0x0015    2 bytes  Tag for this "extra" block type
 * TSize     2 bytes  Size of data that follows
 * RCount    4 bytes  Number of recipients. (inferred)
 * HashAlg   2 bytes  Hash algorithm identifier. (inferred)
 * TData     TSize    Signature Data
</pre> *
 *
 * @NotThreadSafe
 */
internal class X0015_CertificateIdForFile : PKWareExtraHeader(ZipShort(0x0015)) {
    /**
     * Get record count.
     * @return the record count
     */
    var recordCount = 0
        private set

    /**
     * Get hash algorithm.
     * @return the hash algorithm
     */
    var hashAlgorithm: HashAlgorithm? = null
        private set

    @Throws(ZipException::class)
    override fun parseFromCentralDirectoryData(buffer: ByteArray, offset: Int, length: Int) {
        assertMinimalLength(4, length)
        super.parseFromCentralDirectoryData(buffer, offset, length)
        recordCount = getValue(buffer, offset)
        hashAlgorithm = HashAlgorithm.getAlgorithmByCode(getValue(buffer, offset + 2))
    }
}