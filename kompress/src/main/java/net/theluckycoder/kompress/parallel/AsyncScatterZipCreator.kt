package net.theluckycoder.kompress.parallel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import net.theluckycoder.kompress.zip.ScatterZipOutputStream
import net.theluckycoder.kompress.zip.StreamCompressor
import net.theluckycoder.kompress.zip.ZipArchiveEntry
import net.theluckycoder.kompress.zip.ZipArchiveEntryRequest
import net.theluckycoder.kompress.zip.ZipArchiveEntryRequestSupplier
import net.theluckycoder.kompress.zip.ZipArchiveOutputStream
import net.theluckycoder.kompress.zip.ZipMethod
import java.io.File
import java.io.IOException
import java.util.Deque
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import kotlin.coroutines.CoroutineContext

public class AsyncScatterZipCreator @JvmOverloads constructor(
    /**
     * The coroutine context the that will be used to archive individual files in parallel
     */
    coroutineContext: CoroutineContext,
    private val backingStoreSupplier: ScatterGatherBackingStoreSupplier = DefaultBackingStoreSupplier()
) {
    private val streams: Deque<ScatterZipOutputStream> = ConcurrentLinkedDeque()
    private val futures: Deque<Deferred<ScatterZipOutputStream>> = ConcurrentLinkedDeque()
    private val coroutineScope = CoroutineScope(coroutineContext)

    private class DefaultBackingStoreSupplier : ScatterGatherBackingStoreSupplier {
        val storeNumber = AtomicInteger(0)

        override fun get(): ScatterGatherBackingStore {
            val tempFile =
                File.createTempFile("parallelscatter", "n" + storeNumber.incrementAndGet())
            return FileBasedScatterGatherBackingStore(tempFile)
        }
    }

    private val threadLocalScatterStreams = object : ThreadLocal<ScatterZipOutputStream>() {
        override fun initialValue(): ScatterZipOutputStream {
            val backingStore = backingStoreSupplier.get()

            // lifecycle is bound to the newly created ScatterZipOutputStream
            val streamCompressor =
                StreamCompressor.create(Deflater.DEFAULT_COMPRESSION, backingStore)

            return ScatterZipOutputStream(backingStore, streamCompressor).apply {
                streams.add(this)
            }
        }

        override fun get(): ScatterZipOutputStream = super.get()!!
    }

    /**
     * Adds an archive entry to this archive.
     *
     *
     * This method is expected to be called from a single client thread
     *
     * @param zipArchiveEntry The entry to add.
     * @param inputStreamSupplier The source input stream supplier
     */
    public fun addArchiveEntry(
        zipArchiveEntry: ZipArchiveEntry,
        inputStreamSupplier: InputStreamSupplier
    ) {
        submitStreamAwareFunction(createFunction(zipArchiveEntry, inputStreamSupplier))
    }

    /**
     * Adds an archive entry to this archive.
     *
     * This method is expected to be called from a single client thread
     *
     * @param zipArchiveEntryRequestSupplier Should supply the entry to be added.
     */
    public fun addArchiveEntry(zipArchiveEntryRequestSupplier: ZipArchiveEntryRequestSupplier) {
        submitStreamAwareFunction(createFunction(zipArchiveEntryRequestSupplier))
    }

    /**
     * Submit a lambda for compression.
     *
     * @see [createFunction] for details of if/when to use this.
     *
     * @param block The lambda to run, created by [createFunction], possibly wrapped by caller.
     */
    public fun submit(block: () -> Any?) {
        submitStreamAwareFunction {
            block()
            threadLocalScatterStreams.get()
        }
    }

    /**
     * Submit a callable for compression.
     *
     * @see [createFunction] for details of if/when to use this.
     */
    public fun submitStreamAwareFunction(block: () -> ScatterZipOutputStream) {
        futures += coroutineScope.async {
            block()
        }
    }

    /**
     * Create a callable that will compress the given archive entry.
     *
     *
     * This method is expected to be called from a single client thread.
     *
     * Consider using [addArchiveEntry], which wraps this method and [submitStreamAwareFunction].
     * The most common use case for using [createFunction] and [submitStreamAwareFunction] from a
     * client is if you want to wrap the callable in something that can be prioritized by the supplied
     * [ExecutorService], for instance to process large or slow files first.
     * Since the creation of the [ExecutorService] is handled by the client, all of this is up to the client.
     *
     * @param zipArchiveEntry The entry to add.
     * @param inputStreamSupplier The source input stream supplier
     * @return A callable that should subsequently passed to [submitStreamAwareFunction],
     * possibly in a wrapped/adapted from. The value of this callable is not used, but any exceptions happening inside
     * the compression will be propagated through the callable.
     */
    public fun createFunction(
        zipArchiveEntry: ZipArchiveEntry,
        inputStreamSupplier: InputStreamSupplier
    ): () -> ScatterZipOutputStream {
        val method = zipArchiveEntry.method
        require(method != ZipMethod.UNKNOWN_CODE) { "Method must be set on zipArchiveEntry: $zipArchiveEntry" }
        val zipArchiveEntryRequest: ZipArchiveEntryRequest =
            ZipArchiveEntryRequest.createZipArchiveEntryRequest(
                zipArchiveEntry,
                inputStreamSupplier
            )

        return {
            val scatterStream = threadLocalScatterStreams.get()
            scatterStream.addArchiveEntry(zipArchiveEntryRequest)
            scatterStream
        }
    }

    /**
     * Create a lambda function that will compress archive entry supplied by [ZipArchiveEntryRequestSupplier].
     *
     *
     * This method is expected to be called from a single client thread.
     *
     * The same as [createFunction], but the archive entry
     * to be added is supplied by a [ZipArchiveEntryRequestSupplier].
     *
     * @param zipArchiveEntryRequestSupplier Should supply the entry to be added.
     * @return A function that should subsequently passed to [submitStreamAwareFunction], possibly in a wrapped/adapted from. The
     * value of this function is not used, but any exceptions happening inside the compression
     * will be propagated through the function.
     */
    public fun createFunction(zipArchiveEntryRequestSupplier: ZipArchiveEntryRequestSupplier): () -> ScatterZipOutputStream =
        {
            threadLocalScatterStreams.get().apply {
                addArchiveEntry(zipArchiveEntryRequestSupplier.get())
            }

        }

    /**
     * Write the contents this to the target [ZipArchiveOutputStream].
     *
     *
     * It may be beneficial to write things like directories and manifest files to the targetStream
     * before calling this method.
     *
     *
     *
     * Calling this method will cancel the [coroutineScope] used by this class.
     * If any of the [submit], [submitStreamAwareFunction] function are called on this instance, an exception will be thrown.
     *
     * @param targetStream The [ZipArchiveOutputStream] to receive the contents of the scatter streams
     * @throws IOException If writing fails
     */
    @Throws(IOException::class)
    public suspend fun writeTo(targetStream: ZipArchiveOutputStream): Unit = try {
        // Make sure we catch any exceptions from parallel phase
        val awaitedStreams = futures.mapNotNull {
            try {
                it.await()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        coroutineScope.cancel()

        // It is important that all threads terminate before we go on, ensure happens-before relationship
        awaitedStreams.forEach { scatterStream ->
            scatterStream.zipEntryWriter().writeNextZipEntry(targetStream)
        }

        awaitedStreams.forEach {
            it.close()
        }
    } finally {
        closeAll()
    }

    private fun closeAll() {
        for (scatterStream in streams) {
            try {
                scatterStream.close()
            } catch (e: IOException) {
                // no way to properly log this
            }
        }
    }
}
