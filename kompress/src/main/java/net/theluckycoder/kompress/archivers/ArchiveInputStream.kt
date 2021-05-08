package net.theluckycoder.kompress.archivers

import kotlin.Throws
import java.io.IOException
import java.io.InputStream

/**
 * Archive input streams **MUST** override the
 * [read] - or [read] -
 * method so that reading from the stream generates EOF for the end of
 * data in each entry as well as at the end of the file proper.
 *
 *
 * The [getNextEntry] method is used to reset the input stream
 * ready for reading the data from the next entry.
 *
 */
public abstract class ArchiveInputStream : InputStream() {
    private val single = ByteArray(1)
    /**
     * Returns the current number of bytes read from this stream.
     * @return the number of read bytes
     */
    /** holds the number of bytes read in this stream  */
    public var bytesRead: Long = 0
        private set

    /**
     * Returns the next Archive Entry in this Stream.
     *
     * @return the next entry, or `null` if there are no more entries
     * @throws IOException if the next entry could not be read
     */
    @Throws(IOException::class)
    public abstract fun getNextEntry(): ArchiveEntry?
    /*
     * Note that subclasses also implement specific get() methods which
     * return the appropriate class without need for a cast.
     * See SVN revision r743259
     * @return
     * @throws IOException
     */
    // public abstract XXXArchiveEntry getNextXXXEntry() throws IOException;
    /**
     * Reads a byte of data. This method will block until enough input is
     * available.
     *
     * Simply calls the [read] method.
     *
     * MUST be overridden if the [read] method
     * is not overridden; may be overridden otherwise.
     *
     * @return the byte read, or -1 if end of input is reached
     * @throws IOException
     * if an I/O error has occurred
     */
    @Throws(IOException::class)
    override fun read(): Int {
        val num = read(single, 0, 1)
        return if (num == -1) -1 else single[0].toInt() and BYTE_MASK
    }

    /**
     * Increments the counter of already read bytes.
     * Doesn't increment if the EOF has been hit (read == -1)
     *
     * @param read the number of bytes read
     */
    protected fun count(read: Int) {
        count(read.toLong())
    }

    /**
     * Increments the counter of already read bytes.
     * Doesn't increment if the EOF has been hit (read == -1)
     *
     * @param read the number of bytes read
     */
    protected fun count(read: Long) {
        if (read != -1L)
            bytesRead += read
    }

    /**
     * Decrements the counter of already read bytes.
     *
     * @param pushedBack the number of bytes pushed back.
     */
    protected fun pushedBackBytes(pushedBack: Long) {
        bytesRead -= pushedBack
    }

    /**
     * Whether this stream is able to read the given entry.
     *
     *
     *
     * Some archive formats support variants or details that are not supported (yet).
     *
     *
     * @param archiveEntry the entry to test
     * @return This implementation always returns true.
     */
    public open fun canReadEntryData(archiveEntry: ArchiveEntry?): Boolean = true

    public companion object {
        private const val BYTE_MASK = 0xFF
    }
}