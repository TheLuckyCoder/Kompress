package net.theluckycoder.kompress.zip

/**
 * The different modes [ZipArchiveOutputStream] can operate in.
 *
 * @see ZipArchiveOutputStream.setUseZip64
 */
public enum class Zip64Mode {
    /**
     * Use Zip64 extensions for all entries, even if it is clear it is
     * not required.
     */
    Always,

    /**
     * Don't use Zip64 extensions for any entries.
     *
     *
     * This will cause a [Zip64RequiredException] to be
     * thrown if [ZipArchiveOutputStream] detects it needs Zip64
     * support.
     */
    Never,

    /**
     * Use Zip64 extensions for all entries where they are required,
     * don't use them for entries that clearly don't require them.
     */
    AsNeeded
}
