package net.theluckycoder.kompress.zip

/**
 * Various constants used throughout the package.
 */
internal object ZipConstants {
    /** Masks last eight bits  */
    const val BYTE_MASK = 0xFF

    /** length of a ZipShort in bytes  */
    const val SHORT = 2

    /** length of a ZipLong in bytes  */
    const val WORD = 4

    /** length of a ZipEightByteInteger in bytes  */
    const val DWORD = 8

    /** Initial ZIP specification version  */
    const val INITIAL_VERSION = 10

    /**
     * ZIP specification version that introduced DEFLATE compression method.
     */
    const val DEFLATE_MIN_VERSION = 20

    /** ZIP specification version that introduced data descriptor method  */
    const val DATA_DESCRIPTOR_MIN_VERSION = 20

    /** ZIP specification version that introduced ZIP64  */
    const val ZIP64_MIN_VERSION = 45

    /**
     * Value stored in two-byte size and similar fields if ZIP64
     * extensions are used.
     */
    const val ZIP64_MAGIC_SHORT = 0xFFFF

    /**
     * Value stored in four-byte size and similar fields if ZIP64
     * extensions are used.
     */
    const val ZIP64_MAGIC = 0xFFFFFFFFL
}
