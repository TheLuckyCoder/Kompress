package net.theluckycoder.kompress.zip

import java.io.Serializable
import java.util.zip.ZipException

/**
 * Exception thrown when attempting to read or write data for a zip
 * entry that uses ZIP features not supported by this library.
 */
public class UnsupportedZipFeatureException : ZipException {
    /**
     * The unsupported feature that has been used.
     */
    public val feature: Feature

    /**
     * The entry using the unsupported feature.
     */
    @Transient
    public val entry: ZipArchiveEntry?

    /**
     * Creates an exception.
     * @param reason the feature that is not supported
     * @param entry the entry using the feature
     */
    public constructor(reason: Feature, entry: ZipArchiveEntry) : super(
        "Unsupported feature $reason used in entry ${entry.name}"
    ) {
        feature = reason
        this.entry = entry
    }

    /**
     * Creates an exception for archives that use an unsupported
     * compression algorithm.
     * @param method the method that is not supported
     * @param entry the entry using the feature
     */
    public constructor(method: ZipMethod, entry: ZipArchiveEntry) : super(
        "Unsupported compression method ${entry.method} (${method.name}) used in entry ${entry.name}"
    ) {
        feature = Feature.METHOD
        this.entry = entry
    }

    /**
     * Creates an exception when the whole archive uses an unsupported
     * feature.
     *
     * @param reason the feature that is not supported
     */
    public constructor(reason: Feature) : super("Unsupported feature $reason used in archive.") {
        feature = reason
        entry = null
    }

    /**
     * ZIP Features that may or may not be supported.
     */
    public class Feature private constructor(private val name: String) : Serializable {

        override fun toString(): String = name

        public companion object {
            private const val serialVersionUID = 4112582948775420359L

            /**
             * The entry is encrypted.
             */
            @JvmField
            public val ENCRYPTION: Feature = Feature("encryption")

            /**
             * The entry used an unsupported compression method.
             */
            @JvmField
            public val METHOD: Feature = Feature("compression method")

            /**
             * The entry uses a data descriptor.
             */
            @JvmField
            public val DATA_DESCRIPTOR: Feature = Feature("data descriptor")

            /**
             * The archive uses splitting or spanning.
             */
            @JvmField
            public val SPLITTING: Feature = Feature("splitting")

            /**
             * The archive contains entries with unknown compressed size
             * for a compression method that doesn't support detection of
             * the end of the compressed stream.
             */
            @JvmField
            public val UNKNOWN_COMPRESSED_SIZE: Feature = Feature("unknown compressed size")
        }
    }

    public companion object {
        private const val serialVersionUID = 20161219L
    }
}