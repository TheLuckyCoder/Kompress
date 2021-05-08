package net.theluckycoder.kompress.parallel

import java.io.InputStream

/**
 * Supplies input streams.
 *
 * Implementations are required to support thread-handover. While an instance will
 * not be accessed concurrently by multiple threads, it will be called by
 * a different thread than it was created on.
 */
public fun interface InputStreamSupplier {
    /**
     * Supply an input stream for a resource.
     * @return the input stream. Should never null, but may be an empty stream.
     */
    public fun get(): InputStream
}
