package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.zip.ZipShort.Utils.getValue
import java.util.zip.ZipException

/**
 * X.509 Certificate ID and Signature for central directory (0x0016).
 *
 *
 * This field contains the information about which certificate in the PKCS#7
 * store was used to sign the central directory structure. When the Central
 * Directory Encryption feature is enabled for a ZIP file, this record will
 * appear in the Archive Extra Data Record, otherwise it will appear in the
 * first central directory record.
 *
 *
 * Note: all fields stored in Intel low-byte/high-byte order.
 *
 * <pre>
 * Value     Size     Description
 * -----     ----     -----------
 * (CDID)  0x0016    2 bytes  Tag for this "extra" block type
 * TSize     2 bytes  Size of data that follows
 * RCount    4 bytes  Number of recipients. (inferred)
 * HashAlg   2 bytes  Hash algorithm identifier. (inferred)
 * TData     TSize    Data
</pre> *
 *
 * @NotThreadSafe
 */
internal class X0016_CertificateIdForCentralDirectory : PKWareExtraHeader(ZipShort(0x0016)) {
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
        // TODO: double check we really do not want to call super here
        recordCount = getValue(buffer, offset)
        hashAlgorithm = HashAlgorithm.getAlgorithmByCode(getValue(buffer, offset + 2))
    }
}