package net.theluckycoder.kompress.archivers

import java.util.Date

/**
 * Represents an entry of an archive.
 */
public interface ArchiveEntry {

    /**
     * Gets the name of the entry in this archive. May refer to a file or directory or other item.
     *
     *
     * This method returns the raw name as it is stored inside of the archive.
     *
     * @return The name of this entry in the archive.
     */
    public fun getName(): String

    /**
     * Gets the uncompressed size of this entry. May be -1 (SIZE_UNKNOWN) if the size is unknown
     *
     * @return the uncompressed size of this entry.
     */
    public fun getSize(): Long

    /**
     * Returns true if this entry refers to a directory.
     *
     * @return true if this entry refers to a directory.
     */
    public fun isDirectory(): Boolean

    /**
     * Gets the last modified date of this entry.
     *
     * @return the last modified date of this entry.
     */
    public fun getLastModifiedDate(): Date

    public companion object {
        /** Special value indicating that the size is unknown  */
        public const val SIZE_UNKNOWN: Long = -1
    }
}
