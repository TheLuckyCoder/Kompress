package net.theluckycoder.kompress.zip

/**
 * Info-ZIP Unicode Path Extra Field (0x7075):
 *
 *
 * Stores the UTF-8 version of the file name field as stored in the
 * local header and central directory header.
 *
 * @see [PKWARE
 * APPNOTE.TXT, section 4.6.9](https://www.pkware.com/documents/casestudies/APPNOTE.TXT)
 *
 *
 * @NotThreadSafe super-class is not thread-safe
 */
internal class UnicodePathExtraField : AbstractUnicodeExtraField {

    constructor() : super()

    /**
     * Assemble as unicode path extension from the name given as
     * text as well as the encoded bytes actually written to the archive.
     *
     * @param text The file name
     * @param bytes the bytes actually written to the archive
     * @param off The offset of the encoded file name in `bytes`.
     * @param len The length of the encoded file name or comment in
     * `bytes`.
     */
    constructor(text: String, bytes: ByteArray?, off: Int, len: Int) : super(text, bytes, off, len)

    /**
     * Assemble as unicode path extension from the name given as
     * text as well as the encoded bytes actually written to the archive.
     *
     * @param name The file name
     * @param bytes the bytes actually written to the archive
     */
    constructor(name: String, bytes: ByteArray) : super(name, bytes)

    override val headerId = UPATH_ID

    companion object {
        val UPATH_ID = ZipShort(0x7075)
    }
}
