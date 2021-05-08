package net.theluckycoder.kompress.parallel

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 *
 * Store intermediate payload in a scatter-gather scenario.
 * Multiple threads write their payload to a backing store, which can
 * subsequently be reversed to an [InputStream] to be used as input in the
 * gather phase.
 *
 *
 * It is the responsibility of the allocator of an instance of this class
 * to close this. Closing it should clear off any allocated structures
 * and preferably delete files.
 */
public interface ScatterGatherBackingStore : Closeable {

    /**
     * An input stream that contains the scattered payload
     *
     * @return An InputStream, should be closed by the caller of this method.
     * @throws IOException when something fails
     */
    @get:Throws(IOException::class)
    public val inputStream: InputStream

    /**
     * Writes a piece of payload.
     *
     * @param data the data to write
     * @param offset offset inside data to start writing from
     * @param length the amount of data to write
     * @throws IOException when something fails
     */
    @Throws(IOException::class)
    public fun writeOut(data: ByteArray, offset: Int, length: Int)

    /**
     * Closes this backing store for further writing.
     * @throws IOException when something fails
     */
    @Throws(IOException::class)
    public fun closeForWriting()
}
