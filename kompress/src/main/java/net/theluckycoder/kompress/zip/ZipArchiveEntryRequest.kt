package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.parallel.InputStreamSupplier
import java.io.InputStream

/**
 * A Thread-safe representation of a ZipArchiveEntry that is used to add entries to parallel archives.
 */
public class ZipArchiveEntryRequest private constructor(
    /**
     * Gets the underlying entry. Do not use this methods from threads that did not create the instance itself !
     */
    public val zipArchiveEntry: ZipArchiveEntry,
    /*
     * The zipArchiveEntry is not thread safe, and cannot be safely accessed by the getters of this class.
     * It is safely accessible during the construction part of this class and also after the
     * thread pools have been shut down.
     */
    private val payloadSupplier: InputStreamSupplier
) {
    /**
     * The compression method to use
     *
     * @return The compression method to use
     */
    public val method: Int = zipArchiveEntry.method

    /**
     * The payload that will be added to this zip entry
     *
     * @return The input stream.
     */
    public val payloadStream: InputStream
        get() = payloadSupplier.get()

    public companion object {
        /**
         * Create a ZipArchiveEntryRequest
         *
         * @param zipArchiveEntry The entry to use
         * @param payloadSupplier The payload that will be added to the zip entry.
         * @return The newly created request
         */
        public fun createZipArchiveEntryRequest(
            zipArchiveEntry: ZipArchiveEntry,
            payloadSupplier: InputStreamSupplier
        ): ZipArchiveEntryRequest {
            return ZipArchiveEntryRequest(zipArchiveEntry, payloadSupplier)
        }
    }
}
