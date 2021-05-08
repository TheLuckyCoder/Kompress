package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.parallel.FileBasedScatterGatherBackingStore
import net.theluckycoder.kompress.parallel.ScatterGatherBackingStore
import net.theluckycoder.kompress.utils.BoundedInputStream
import java.io.*
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater

public class ScatterZipOutputStream(
    private val backingStore: ScatterGatherBackingStore,
    private val streamCompressor: StreamCompressor
) : Closeable {

    private val items: Queue<CompressedEntry> = ConcurrentLinkedQueue()
    private val isClosed = AtomicBoolean()
    private val zipEntryWriterDelegate = lazy { ZipEntryWriter(this) }
    private val zipEntryWriter by zipEntryWriterDelegate

    private class CompressedEntry(
        val zipArchiveEntryRequest: ZipArchiveEntryRequest,
        val crc: Long,
        val compressedSize: Long,
        val size: Long
    ) {
        /**
         * Update the original [ZipArchiveEntry] with sizes/crc
         * Do not use this methods from threads that did not create the instance itself !
         *
         * @return the zipArchiveEntry that is basis for this request
         */
        fun transferToArchiveEntry(): ZipArchiveEntry {
            val entry = zipArchiveEntryRequest.zipArchiveEntry
            entry.compressedSize = compressedSize
            entry.size = size
            entry.crc = crc
            entry.method = zipArchiveEntryRequest.method
            return entry
        }
    }

    /**
     * Add an archive entry to this scatter stream.
     *
     * @param zipArchiveEntryRequest The entry to write.
     * @throws IOException If writing fails
     */
    @Throws(IOException::class)
    public fun addArchiveEntry(zipArchiveEntryRequest: ZipArchiveEntryRequest) {
        zipArchiveEntryRequest.payloadStream.use { payloadStream ->
            streamCompressor.deflate(payloadStream, zipArchiveEntryRequest.method)
        }

        items.add(
            CompressedEntry(
                zipArchiveEntryRequest, streamCompressor.crc32,
                streamCompressor.bytesWrittenForLastEntry, streamCompressor.bytesRead
            )
        )
    }

    /**
     * Write the contents of this scatter stream to a target archive.
     *
     * @param target The archive to receive the contents of this [ScatterZipOutputStream].
     * @throws IOException If writing fails
     * @see .zipEntryWriter
     */
    @Throws(IOException::class)
    public fun writeTo(target: ZipArchiveOutputStream) {
        backingStore.closeForWriting()
        backingStore.inputStream.use { data ->
            for (compressedEntry in items) {
                BoundedInputStream(data, compressedEntry.compressedSize).use { rawStream ->
                    target.addRawArchiveEntry(compressedEntry.transferToArchiveEntry(), rawStream)
                }
            }
        }
    }

    public class ZipEntryWriter(scatter: ScatterZipOutputStream) : Closeable {
        private val itemsIterator: Iterator<CompressedEntry>
        private val itemsIteratorData: InputStream

        init {
            scatter.backingStore.closeForWriting()
            itemsIterator = scatter.items.iterator()
            itemsIteratorData = scatter.backingStore.inputStream
        }

        @Throws(IOException::class)
        override fun close() {
            itemsIteratorData.close()
        }

        @Throws(IOException::class)
        public fun writeNextZipEntry(target: ZipArchiveOutputStream) {
            val compressedEntry = itemsIterator.next()
            BoundedInputStream(itemsIteratorData, compressedEntry.compressedSize).use { rawStream ->
                target.addRawArchiveEntry(compressedEntry.transferToArchiveEntry(), rawStream)
            }
        }
    }

    /**
     * Get a zip entry writer for this scatter stream.
     *
     * @return the ZipEntryWriter created on first call of the method
     * @throws IOException If getting scatter stream input stream
     */
    @Throws(IOException::class)
    public fun zipEntryWriter(): ZipEntryWriter = zipEntryWriter

    /**
     * Closes this stream, freeing all resources involved in the creation of this stream.
     *
     * @throws IOException If closing fails
     */
    @Throws(IOException::class)
    override fun close() {
        if (!isClosed.compareAndSet(false, true)) {
            return
        }
        streamCompressor.use {
            if (zipEntryWriterDelegate.isInitialized()) {
                zipEntryWriter.close()
            }
            backingStore.close()
        }
    }

    public companion object {
        /**
         * Create a [ScatterZipOutputStream] with default compression level that is backed by a file
         *
         * @param file The file to offload compressed data into.
         * @return A ScatterZipOutputStream that is ready for use.
         * @throws FileNotFoundException if the file cannot be found
         */
        @JvmOverloads
        @Throws(FileNotFoundException::class)
        public fun fileBased(file: File, compressionLevel: Int = Deflater.DEFAULT_COMPRESSION): ScatterZipOutputStream {
            val bs: ScatterGatherBackingStore = FileBasedScatterGatherBackingStore(file)
            // lifecycle is bound to the ScatterZipOutputStream returned
            val sc = StreamCompressor.create(compressionLevel, bs)
            return ScatterZipOutputStream(bs, sc)
        }
    }
}
