package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.zip.ZipLong.Utils.getBytes
import net.theluckycoder.kompress.zip.ZipLong.Utils.getValue
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipException

/**
 * A common base class for Unicode extra information extra fields.
 * @NotThreadSafe
 */
internal abstract class AbstractUnicodeExtraField : ZipExtraField {

    /**
     * The CRC32 checksum of the file name or comment as
     * encoded in the central directory of the zip file.
     */
    var nameCRC32: Long = 0L
        set(value) {
            field = value
            data = null
        }

    /**
     * The UTF-8 encoded name
     */
    var unicodeName: ByteArray? = null
        get() = field?.copyOf()
        set(value) {
            field = value?.copyOf()
            data = null
        }
    private var data: ByteArray? = null


    protected constructor()

    /**
     * Assemble as unicode extension from the name/comment and
     * encoding of the original zip entry.
     *
     * @param text The file name or comment.
     * @param bytes The encoded of the file name or comment in the zip file.
     * @param offset The offset of the encoded file name or comment in [bytes].
     * @param length The length of the encoded file name or comment in [bytes].
     */
    protected constructor(text: String, bytes: ByteArray?, offset: Int, length: Int) {
        val crc32 = CRC32()
        crc32.update(bytes, offset, length)
        nameCRC32 = crc32.value
        unicodeName = text.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Assemble as unicode extension from the name/comment and
     * encoding of the original zip entry.
     *
     * @param text The file name or comment.
     * @param bytes The encoded of the file name or comment in the zip
     * file.
     */
    protected constructor(text: String, bytes: ByteArray) : this(text, bytes, 0, bytes.size)

    private fun assembleData() {
        val unicodeName = unicodeName ?: return
        val data = ByteArray(5 + unicodeName.size)
        this.data = data

        // version 1
        data[0] = 0x01

        getBytes(nameCRC32).copyInto(data, destinationOffset = 1)
        unicodeName.copyInto(data, destinationOffset = 5)
    }

    override fun getCentralDirectoryData(): ByteArray? {
        if (data == null) {
            assembleData()
        }

        return data?.copyOf()
    }

    override fun getCentralDirectoryLength(): ZipShort {
        if (data == null)
            assembleData()

        return ZipShort(data?.size ?: 0)
    }

    override fun getLocalFileDataData(): ByteArray? = getCentralDirectoryData()

    override fun getLocalFileDataLength(): ZipShort = getCentralDirectoryLength()

    @Throws(ZipException::class)
    override fun parseFromLocalFileData(buffer: ByteArray, offset: Int, length: Int) {
        if (length < 5)
            throw ZipException("UniCode path extra data must have at least 5 bytes.")

        val version = buffer[offset].toInt()
        if (version != 0x01)
            throw ZipException("Unsupported version [$version] for UniCode path extra data.")

        nameCRC32 = getValue(buffer, offset + 1)
        unicodeName = ByteArray(length - 5)
        System.arraycopy(buffer, offset + 5, unicodeName!!, 0, length - 5)
        data = null
    }

    /**
     * Doesn't do anything special since this class always uses the
     * same data in central directory and local file data.
     */
    @Throws(ZipException::class)
    override fun parseFromCentralDirectoryData(buffer: ByteArray, offset: Int, length: Int) {
        parseFromLocalFileData(buffer, offset, length)
    }
}
