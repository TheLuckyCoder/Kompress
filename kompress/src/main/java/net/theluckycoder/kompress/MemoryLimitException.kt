package net.theluckycoder.kompress

import java.io.IOException

/**
 * If a stream checks for estimated memory allocation, and the estimate
 * goes above the memory limit, this is thrown.  This can also be thrown
 * if a stream tries to allocate a byte array that is larger than
 * the allowable limit.
 */
public class MemoryLimitException @JvmOverloads constructor(
    // Long instead of Int to account for overflow for corrupt files
    memoryNeededInKb: Long,
    memoryLimitInKb: Int,
    e: Exception? = null
) : IOException(buildMessage(memoryNeededInKb, memoryLimitInKb), e) {

    public companion object {
        private const val serialVersionUID = 1L

        private fun buildMessage(memoryNeededInKb: Long, memoryLimitInKb: Int): String {
            return (memoryNeededInKb.toString() + " kb of memory would be needed; limit was "
                    + memoryLimitInKb + " kb. " +
                    "If the file is not corrupt, consider increasing the memory limit.")
        }
    }
}
