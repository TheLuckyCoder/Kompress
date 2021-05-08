package net.theluckycoder.kompress.zip

/**
 * PKCS#7 Store for X.509 Certificates (0x0014).
 *
 *
 * This field MUST contain information about each of the certificates files may
 * be signed with. When the Central Directory Encryption feature is enabled for
 * a ZIP file, this record will appear in the Archive Extra Data Record,
 * otherwise it will appear in the first central directory record and will be
 * ignored in any other record.
 *
 *
 * Note: all fields stored in Intel low-byte/high-byte order.
 *
 * <pre>
 * Value     Size     Description
 * -----     ----     -----------
 * (Store) 0x0014    2 bytes  Tag for this "extra" block type
 * TSize     2 bytes  Size of the store data
 * TData     TSize    Data about the store
</pre> *
 *
 * @NotThreadSafe
 */
internal class X0014_X509Certificates : PKWareExtraHeader(ZipShort(0x0014))
