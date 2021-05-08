package net.theluckycoder.kompress.parallel

import java.io.IOException

/**
 * Supplies [ScatterGatherBackingStore] instances.
 */
public fun interface ScatterGatherBackingStoreSupplier {

    /**
     * Create a ScatterGatherBackingStore.
     *
     * @return a ScatterGatherBackingStore, not null
     * @throws IOException when something fails
     */
    @Throws(IOException::class)
    public fun get(): ScatterGatherBackingStore
}
