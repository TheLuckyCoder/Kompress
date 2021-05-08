package net.theluckycoder.kompress.zip

import java.util.zip.ZipException

/**
 * Exception thrown when attempting to write data that requires Zip64
 * support to an archive and [ZipArchiveOutputStream.setUseZip64] has been set to [Zip64Mode.Never].
 */
public class Zip64RequiredException(reason: String?) : ZipException(reason) {

    public companion object {
        private const val serialVersionUID = 20110809L

        /**
         * Helper to format "entry too big" messages.
         */
        internal fun getEntryTooBigMessage(ze: ZipArchiveEntry): String =
            "${ze.name}'s size exceeds the limit of 4GByte."

        internal const val NUMBER_OF_THIS_DISK_TOO_BIG_MESSAGE =
            "Number of the disk of End Of Central Directory exceeds the limmit of 65535."
        internal const val NUMBER_OF_THE_DISK_OF_CENTRAL_DIRECTORY_TOO_BIG_MESSAGE =
            "Number of the disk with the start of Central Directory exceeds the limmit of 65535."
        internal const val TOO_MANY_ENTRIES_ON_THIS_DISK_MESSAGE =
            "Number of entries on this disk exceeds the limmit of 65535."
        internal const val SIZE_OF_CENTRAL_DIRECTORY_TOO_BIG_MESSAGE =
            "The size of the entire central directory exceeds the limit of 4GByte."
        internal const val ARCHIVE_TOO_BIG_MESSAGE = "Archive's size exceeds the limit of 4GByte."
        internal const val TOO_MANY_ENTRIES_MESSAGE = "Archive contains more than 65535 entries."
    }
}
