package net.theluckycoder.kompress.archivers

import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Archive output stream implementations are expected to override the
 * [write] method to improve performance.
 * They should also override [close] to ensure that any necessary
 * trailers are added.
 *
 *
 * The normal sequence of calls when working with ArchiveOutputStreams is:
 *
 *  * Create ArchiveOutputStream object,
 *  * optionally write SFX header (Zip only),
 *  * repeat as needed:
 *
 *  * [putArchiveEntry] (writes entry header),
 *  * [write] (writes entry data, as often as needed),
 *  * [closeArchiveEntry] (closes entry),
 *
 *
 *  *  [finish] (ends the addition of entries),
 *  *  optionally write additional data, provided format supports it,
 *  *  [close].
 *
 */
public abstract class ArchiveOutputStream : OutputStream() {

    /** Temporary buffer used for the [write] method  */
    private val oneByte = ByteArray(1)
    /**
     * Returns the current number of bytes written to this stream.
     * @return the number of written bytes
     */
    /** holds the number of bytes written to this stream  */
    public var bytesWritten: Long = 0
        private set

    // Methods specific to ArchiveOutputStream
    /**
     * Writes the headers for an archive entry to the output stream.
     * The caller must then write the content to the stream and call
     * [closeArchiveEntry] to complete the process.
     *
     * @param entry describes the entry
     * @throws IOException if an I/O error occurs
     */
    @Throws(IOException::class)
    public abstract fun putArchiveEntry(entry: ArchiveEntry)

    /**
     * Closes the archive entry, writing any trailer information that may
     * be required.
     * @throws IOException if an I/O error occurs
     */
    @Throws(IOException::class)
    public abstract fun closeArchiveEntry()

    /**
     * Finishes the addition of entries to this stream, without closing it.
     * Additional data can be written, if the format supports it.
     *
     * @throws IOException if the user forgets to close the entry.
     */
    @Throws(IOException::class)
    public abstract fun finish()

    /**
     * Create an archive entry using the inputFile and entryName provided.
     *
     * @param inputFile the file to create the entry from
     * @param entryName name to use for the entry
     * @return the ArchiveEntry set up with details from the file
     *
     * @throws IOException if an I/O error occurs
     */
    @Throws(IOException::class)
    public abstract fun createArchiveEntry(inputFile: File, entryName: String): ArchiveEntry?

    // Generic implementations of OutputStream methods that may be useful to sub-classes
    /**
     * Writes a byte to the current archive entry.
     *
     *
     * This method simply calls `write( byte[], 0, 1 )`.
     *
     *
     * MUST be overridden if the [write] method
     * is not overridden; may be overridden otherwise.
     *
     * @param b The byte to be written.
     * @throws IOException on error
     */
    @Throws(IOException::class)
    override fun write(b: Int) {
        oneByte[0] = (b and BYTE_MASK).toByte()
        write(oneByte, 0, 1)
    }

    /**
     * Increments the counter of already written bytes.
     * Doesn't increment if EOF has been hit (`written == -1`).
     *
     * @param written the number of bytes written
     */
    protected fun count(written: Int) {
        count(written.toLong())
    }

    /**
     * Increments the counter of already written bytes.
     * Doesn't increment if EOF has been hit (`written == -1`).
     *
     * @param written the number of bytes written
     */
    protected fun count(written: Long) {
        if (written != -1L)
            bytesWritten += written
    }

    /**
     * Whether this stream is able to write the given entry.
     *
     *
     * Some archive formats support variants or details that are
     * not supported (yet).
     *
     * @param archiveEntry
     * the entry to test
     * @return This implementation always returns true.
     */
    public open fun canWriteEntryData(archiveEntry: ArchiveEntry?): Boolean = true

    public companion object {
        private const val BYTE_MASK = 0xFF
    }
}
