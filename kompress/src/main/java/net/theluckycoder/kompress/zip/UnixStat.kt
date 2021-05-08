package net.theluckycoder.kompress.zip

/**
 * Constants from stat.h on Unix systems.
 */
internal object UnixStat {

    /**
     * Bits used for permissions (and sticky bit)
     */
    const val PERM_MASK = 4095

    /**
     * Bits used to indicate the file system object type.
     */
    const val FILE_TYPE_FLAG = 61440

    /**
     * Indicates symbolic links.
     */
    const val LINK_FLAG = 40960

    /**
     * Indicates plain files.
     */
    const val FILE_FLAG = 32768

    /**
     * Indicates directories.
     */
    const val DIR_FLAG = 16384
    // ----------------------------------------------------------
    // somewhat arbitrary choices that are quite common for shared
    // installations
    // -----------------------------------------------------------
    /**
     * Default permissions for symbolic links.
     */
    const val DEFAULT_LINK_PERM = 511

    /**
     * Default permissions for directories.
     */
    const val DEFAULT_DIR_PERM = 493

    /**
     * Default permissions for plain files.
     */
    const val DEFAULT_FILE_PERM = 420
}
