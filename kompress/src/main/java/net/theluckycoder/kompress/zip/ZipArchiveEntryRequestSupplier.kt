package net.theluckycoder.kompress.zip

/**
 * Supplies [ZipArchiveEntryRequest].
 *
 * Implementations are required to support thread-handover. While an instance will
 * not be accessed concurrently by multiple threads, it will be called by
 * a different thread than it was created on.
 */
public fun interface ZipArchiveEntryRequestSupplier {

    /**
     * Supply a [ZipArchiveEntryRequest] to be added to a parallel archive.
     * @return The [ZipArchiveEntryRequest] instance.
     */
    public fun get(): ZipArchiveEntryRequest
}
