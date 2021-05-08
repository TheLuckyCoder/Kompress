package net.theluckycoder.kompress.zip

/**
 * Info-ZIP Unicode Comment Extra Field (0x6375):
 *
 *
 * Stores the UTF-8 version of the file comment as stored in the
 * central directory header.
 *
 * @see [PKWARE APPNOTE.TXT, section 4.6.8](https://www.pkware.com/documents/casestudies/APPNOTE.TXT)
 *
 *
 * @NotThreadSafe super-class is not thread-safe
 */
internal class UnicodeCommentExtraField : AbstractUnicodeExtraField {

    constructor() : super()

    /**
     * Assemble as unicode comment extension from the name given as
     * text as well as the encoded bytes actually written to the archive.
     *
     * @param text The file name
     * @param bytes the bytes actually written to the archive
     * @param off The offset of the encoded comment in `bytes`.
     * @param len The length of the encoded comment or comment in
     * `bytes`.
     */
    constructor(
        text: String, bytes: ByteArray?, off: Int,
        len: Int
    ) : super(text, bytes, off, len)

    /**
     * Assemble as unicode comment extension from the comment given as
     * text as well as the bytes actually written to the archive.
     *
     * @param comment The file comment
     * @param bytes the bytes actually written to the archive
     */
    constructor(comment: String, bytes: ByteArray) : super(comment, bytes)

    override val headerId = UCOM_ID

    companion object {
        val UCOM_ID = ZipShort(0x6375)
    }
}
