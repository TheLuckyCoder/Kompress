package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.zip.ZipShort.Utils.getBytes
import net.theluckycoder.kompress.zip.ZipShort.Utils.getValue
import net.theluckycoder.kompress.zip.ZipShort.Utils.putShort
import java.util.zip.ZipException

/**
 * An extra field who's sole purpose is to align and pad the local file header
 * so that the entry's data starts at a certain position.
 *
 *
 * The padding content of the padding is ignored and not retained
 * when reading a padding field.
 *
 *
 * This enables Commons Compress to create "aligned" archives
 * similar to Android's zipalign command line tool.
 *
 * @see "https://developer.android.com/studio/command-line/zipalign.html"
 *
 * @see ZipArchiveEntry.alignment
 */
internal class ResourceAlignmentExtraField : ZipExtraField {

    /**
     * Requested alignment.
     */
    var alignment: Short = 0
        private set

    /**
     * Indicates whether method change is allowed when re-compressing the zip file.
     */
    var allowMethodChange = false
        private set
    private var padding = 0

    constructor()

    @JvmOverloads
    constructor(alignment: Int, allowMethodChange: Boolean = false, padding: Int = 0) {
        require(!(alignment < 0 || alignment > 0x7fff)) { "Alignment must be between 0 and 0x7fff, was: $alignment" }
        require(padding >= 0) { "Padding must not be negative, was: $padding" }
        this.alignment = alignment.toShort()
        this.allowMethodChange = allowMethodChange
        this.padding = padding
    }

    override val headerId: ZipShort = ID

    override fun getLocalFileDataLength(): ZipShort = ZipShort(BASE_SIZE + padding)

    override fun getCentralDirectoryLength(): ZipShort = ZipShort(BASE_SIZE)

    override fun getLocalFileDataData(): ByteArray {
        val content = ByteArray(BASE_SIZE + padding)
        putShort(
            alignment.toInt() or if (allowMethodChange) ALLOW_METHOD_MESSAGE_CHANGE_FLAG else 0,
            content, 0
        )
        return content
    }

    override fun getCentralDirectoryData(): ByteArray =
        getBytes(alignment.toInt() or if (allowMethodChange) ALLOW_METHOD_MESSAGE_CHANGE_FLAG else 0)

    @Throws(ZipException::class)
    override fun parseFromLocalFileData(buffer: ByteArray, offset: Int, length: Int) {
        parseFromCentralDirectoryData(buffer, offset, length)
        padding = length - BASE_SIZE
    }

    @Throws(ZipException::class)
    override fun parseFromCentralDirectoryData(buffer: ByteArray, offset: Int, length: Int) {
        if (length < BASE_SIZE) {
            throw ZipException("Too short content for ResourceAlignmentExtraField (0xa11e): $length")
        }
        val alignmentValue = getValue(buffer, offset)
        alignment = (alignmentValue and ALLOW_METHOD_MESSAGE_CHANGE_FLAG - 1).toShort()
        allowMethodChange = alignmentValue and ALLOW_METHOD_MESSAGE_CHANGE_FLAG != 0
    }

    companion object {

        /**
         * Extra field id used for storing alignment and padding.
         */
        val ID = ZipShort(0xa11e)

        const val BASE_SIZE = 2
        private const val ALLOW_METHOD_MESSAGE_CHANGE_FLAG = 0x8000
    }
}
