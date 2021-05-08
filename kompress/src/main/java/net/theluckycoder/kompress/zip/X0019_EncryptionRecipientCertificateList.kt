package net.theluckycoder.kompress.zip

/**
 * PKCS#7 Encryption Recipient Certificate List (0x0019).
 *
 *
 * This field MAY contain information about each of the certificates used in
 * encryption processing and it can be used to identify who is allowed to
 * decrypt encrypted files. This field should only appear in the archive extra
 * data record. This field is not required and serves only to aid archive
 * modifications by preserving public encryption key data. Individual security
 * requirements may dictate that this data be omitted to deter information
 * exposure.
 *
 *
 * Note: all fields stored in Intel low-byte/high-byte order.
 *
 * <pre>
 * Value     Size     Description
 * -----     ----     -----------
 * (CStore) 0x0019    2 bytes  Tag for this "extra" block type
 * TSize     2 bytes  Size of the store data
 * Version   2 bytes  Format version number - must 0x0001 at this time
 * CStore    (var)    PKCS#7 data blob
</pre> *
 *
 *
 * **See the section describing the Strong Encryption Specification for
 * details. Refer to the section in this document entitled
 * "Incorporating PKWARE Proprietary Technology into Your Product" for more
 * information.**
 *
 * @NotThreadSafe
 */
internal class X0019_EncryptionRecipientCertificateList : PKWareExtraHeader(ZipShort(0x0019))
