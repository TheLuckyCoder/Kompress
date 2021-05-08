package net.theluckycoder.kompress.zip

import android.util.SparseArray
import java.util.zip.ZipEntry

/**
 * List of known compression methods
 *
 * Many of these methods are currently not supported by commons compress
 */
public enum class ZipMethod(public val code: Int) {

    /**
     * Compression method 0 for uncompressed entries.
     *
     * @see ZipEntry.STORED
     */
    STORED(ZipEntry.STORED),

    /**
     * UnShrinking.
     * dynamic Lempel-Ziv-Welch-Algorithm
     *
     * @see [Explanation of fields: compression
     * method:
    ](https://www.pkware.com/documents/casestudies/APPNOTE.TXT) */
    UNSHRINKING(1),

    /**
     * Reduced with compression factor 1.
     *
     * @see [Explanation of fields: compression
     * method:
    ](https://www.pkware.com/documents/casestudies/APPNOTE.TXT) */
    EXPANDING_LEVEL_1(2),

    /**
     * Reduced with compression factor 2.
     *
     * @see [Explanation of fields: compression
     * method:
    ](https://www.pkware.com/documents/casestudies/APPNOTE.TXT) */
    EXPANDING_LEVEL_2(3),

    /**
     * Reduced with compression factor 3.
     *
     * @see [Explanation of fields: compression
     * method:
    ](https://www.pkware.com/documents/casestudies/APPNOTE.TXT) */
    EXPANDING_LEVEL_3(4),

    /**
     * Reduced with compression factor 4.
     *
     * @see [Explanation of fields: compression
     * method:
    ](https://www.pkware.com/documents/casestudies/APPNOTE.TXT) */
    EXPANDING_LEVEL_4(5),

    /**
     * Imploding.
     *
     * @see [Explanation of fields: compression
     * method:
    ](https://www.pkware.com/documents/casestudies/APPNOTE.TXT) */
    IMPLODING(6),

    /**
     * Tokenization.
     *
     * @see [Explanation of fields: compression
     * method:
    ](https://www.pkware.com/documents/casestudies/APPNOTE.TXT) */
    TOKENIZATION(7),

    /**
     * Compression method 8 for compressed (deflated) entries.
     *
     * @see ZipEntry.DEFLATED
     */
    DEFLATED(ZipEntry.DEFLATED),

    /**
     * Compression Method 9 for enhanced deflate.
     *
     * @see [https://www.winzip.com/wz54.htm](https://www.winzip.com/wz54.htm)
     */
    ENHANCED_DEFLATED(9),

    /**
     * PKWARE Data Compression Library Imploding.
     *
     * @see [https://www.winzip.com/wz54.htm](https://www.winzip.com/wz54.htm)
     */
    PKWARE_IMPLODING(10),

    /**
     * Compression Method 12 for bzip2.
     *
     * @see [https://www.winzip.com/wz54.htm](https://www.winzip.com/wz54.htm)
     */
    BZIP2(12),

    /**
     * Compression Method 14 for LZMA.
     *
     * @see [https://www.7-zip.org/sdk.html](https://www.7-zip.org/sdk.html)
     *
     * @see [https://www.winzip.com/wz54.htm](https://www.winzip.com/wz54.htm)
     */
    LZMA(14),

    /**
     * Compression Method 95 for XZ.
     *
     * @see [https://www.winzip.com/wz54.htm](https://www.winzip.com/wz54.htm)
     */
    XZ(95),

    /**
     * Compression Method 96 for Jpeg compression.
     *
     * @see [https://www.winzip.com/wz54.htm](https://www.winzip.com/wz54.htm)
     */
    JPEG(96),

    /**
     * Compression Method 97 for WavPack.
     *
     * @see [https://www.winzip.com/wz54.htm](https://www.winzip.com/wz54.htm)
     */
    WAVPACK(97),

    /**
     * Compression Method 98 for PPMd.
     *
     * @see [https://www.winzip.com/wz54.htm](https://www.winzip.com/wz54.htm)
     */
    PPMD(98),

    /**
     * Compression Method 99 for AES encryption.
     *
     * @see [https://www.winzip.com/wz54.htm](https://www.winzip.com/wz54.htm)
     */
    AES_ENCRYPTED(99),

    /**
     * Unknown compression method.
     */
    UNKNOWN;

    constructor() : this(UNKNOWN_CODE)

    public companion object {
        public const val UNKNOWN_CODE: Int = -1
        private val codeToEnum = SparseArray<ZipMethod>()

        /**
         * @return the [ZipMethod] for the given code or null if the
         * method is not known.
         */
        @JvmStatic
        public fun getMethodByCode(code: Int): ZipMethod = codeToEnum[code]

        init {
            for (method in values())
                codeToEnum.put(method.code, method)
        }
    }
}
