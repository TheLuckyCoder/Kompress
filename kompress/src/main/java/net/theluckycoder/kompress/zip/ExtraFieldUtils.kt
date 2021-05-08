package net.theluckycoder.kompress.zip

import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipException

/**
 * ZipExtraField related methods
 */
internal object ExtraFieldUtils {

    private const val WORD = 4

    /**
     * Static registry of known extra fields.
     */
    private val implementations: MutableMap<ZipShort, Class<*>> = ConcurrentHashMap()

    init {
        // Create an instance of these classes here since [Class<*>.newInstance] does not seen work properly on Android
        // TODO Look more into this issue
        register(AsiExtraField())
        register(X5455_ExtendedTimestamp())
        register(X7875_NewUnix())
        register(JarMarker())
        register(UnicodePathExtraField())
        register(UnicodeCommentExtraField())
        register(Zip64ExtendedInformationExtraField())
        register(X000A_NTFS())
        register(X0014_X509Certificates())
        register(X0015_CertificateIdForFile())
        register(X0016_CertificateIdForCentralDirectory())
        register(X0017_StrongEncryptionHeader())
        register(X0019_EncryptionRecipientCertificateList())
        register(ResourceAlignmentExtraField())
    }

    /**
     * Register a ZipExtraField implementation.
     *
     *
     * The given class must have a no-arg constructor and implement the [ZipExtraField].
     * @param zipExtraField the class to register
     */
    private fun register(zipExtraField: ZipExtraField) {
        implementations[zipExtraField.headerId] = zipExtraField::class.java
    }

    /**
     * Create an instance of the appropriate ExtraField, falls back to
     * [UnrecognizedExtraField].
     * @param headerId the header identifier
     * @return an instance of the appropriate ExtraField
     * @throws InstantiationException if unable to instantiate the class
     * @throws IllegalAccessException if not allowed to instantiate the class
     */
    @JvmStatic
    @Throws(InstantiationException::class, IllegalAccessException::class)
    fun createExtraField(headerId: ZipShort): ZipExtraField {
        createExtraFieldNoDefault(headerId)?.let { field ->
            return field
        }
        return UnrecognizedExtraField(headerId)
    }

    /**
     * Create an instance of the appropriate ExtraField.
     * @param headerId the header identifier
     * @return an instance of the appropriate ExtraField or null if
     * the id is not supported
     * @throws InstantiationException if unable to instantiate the class
     * @throws IllegalAccessException if not allowed to instantiate the class
     */
    @Throws(InstantiationException::class, IllegalAccessException::class)
    fun createExtraFieldNoDefault(headerId: ZipShort): ZipExtraField? {
        return implementations[headerId]?.newInstance() as? ZipExtraField
    }

    /**
     * Split the array into ExtraFields and populate them with the
     * given data.
     * @param data an array of bytes
     * @param parsingBehavior controls parsing of extra fields.
     * @param local whether data originates from the local file data
     * or the central directory
     * @return an array of ExtraFields
     * @throws ZipException on error
     */
    @JvmStatic
    @Throws(ZipException::class)
    fun parse(
        data: ByteArray, local: Boolean, parsingBehavior: ExtraFieldParsingBehavior
    ): Array<ZipExtraField> {
        val list = mutableListOf<ZipExtraField>()
        var start = 0

        LOOP@ while (start <= data.size - WORD) {
            val headerId = ZipShort(data, start)
            val length = ZipShort(data, start + 2).value
            if (start + WORD + length > data.size) {
                val field = parsingBehavior.onUnparseableExtraField(
                    data, start, data.size - start,
                    local, length
                )
                if (field != null) {
                    list.add(field)
                }
                // since we cannot parse the data we must assume
                // the extra field consumes the whole rest of the
                // available data
                break@LOOP
            }

            start += try {
                val ze = parsingBehavior.createExtraField(headerId)
                list.add(parsingBehavior.fill(ze, data, start + WORD, length, local))
                length + WORD
            } catch (ie: InstantiationException) {
                throw (ZipException(ie.message).initCause(ie) as ZipException)
            } catch (ie: IllegalAccessException) {
                throw (ZipException(ie.message).initCause(ie) as ZipException)
            }
        }

        return list.toTypedArray()
    }

    /**
     * Merges the local file data fields of the given ZipExtraFields.
     * @param data an array of ExtraFiles
     * @return an array of bytes
     */
    @JvmStatic
    fun mergeLocalFileDataData(data: Array<ZipExtraField>): ByteArray {
        val lastIsUnparseableHolder = (data.isNotEmpty()
                && data[data.size - 1] is UnparseableExtraFieldData)
        val regularExtraFieldCount = if (lastIsUnparseableHolder) data.size - 1 else data.size
        var sum = WORD * regularExtraFieldCount
        for (element in data) {
            sum += element.getLocalFileDataLength().value
        }
        val result = ByteArray(sum)
        var start = 0
        for (i in 0 until regularExtraFieldCount) {
            System.arraycopy(
                data[i].headerId.bytes,
                0, result, start, 2
            )
            System.arraycopy(
                data[i].getLocalFileDataLength().bytes,
                0, result, start + 2, 2
            )
            start += WORD
            val local = data[i].getLocalFileDataData()
            if (local != null) {
                local.copyInto(result, destinationOffset = start)
                start += local.size
            }
        }
        if (lastIsUnparseableHolder) {
            val local = data[data.size - 1].getLocalFileDataData()
            local?.copyInto(result, destinationOffset = start)
        }
        return result
    }

    /**
     * Merges the central directory fields of the given ZipExtraFields.
     * @param data an array of ExtraFields
     * @return an array of bytes
     */
    @JvmStatic
    fun mergeCentralDirectoryData(data: Array<ZipExtraField>): ByteArray {
        val lastIsUnparseableHolder = data.isNotEmpty() && data[data.size - 1] is UnparseableExtraFieldData
        val regularExtraFieldCount = if (lastIsUnparseableHolder) data.size - 1 else data.size
        val sum = WORD * regularExtraFieldCount + data.sumOf { it.getCentralDirectoryLength().value }

        val result = ByteArray(sum)
        var start = 0
        for (i in 0 until regularExtraFieldCount) {
            data[i].headerId.bytes.copyInto(result, destinationOffset = start)
            data[i].getCentralDirectoryLength().bytes.copyInto(result, destinationOffset = start + 2)
            start += WORD

            val central = data[i].getCentralDirectoryData()
            if (central != null) {
                central.copyInto(result, destinationOffset = start)
                start += central.size
            }
        }
        if (lastIsUnparseableHolder) {
            val central = data[data.size - 1].getCentralDirectoryData()
            central?.copyInto(result, destinationOffset = start)
        }
        return result
    }

    /**
     * Fills in the extra field data into the given instance.
     *
     *
     * Calls [ZipExtraField.parseFromCentralDirectoryData] or [ZipExtraField.parseFromLocalFileData] internally and wraps any [ArrayIndexOutOfBoundsException] thrown into a [ZipException].
     *
     * @param ze the extra field instance to fill
     * @param data the array of extra field data
     * @param offset offset into data where this field's data starts
     * @param length the length of this field's data
     * @param local whether the extra field data stems from the local
     * file header. If this is false then the data is part if the
     * central directory header extra data.
     * @return the filled field, will never be `null`
     * @throws ZipException if an error occurs
     */
    @JvmStatic
    @Throws(ZipException::class)
    fun fillExtraField(
        ze: ZipExtraField, data: ByteArray, offset: Int, length: Int, local: Boolean
    ): ZipExtraField {
        return try {
            if (local) {
                ze.parseFromLocalFileData(data, offset, length)
            } else {
                ze.parseFromCentralDirectoryData(data, offset, length)
            }
            ze
        } catch (e: ArrayIndexOutOfBoundsException) {
            throw (ZipException(
                "Failed to parse corrupt ZIP extra field of type ${ze.headerId.value.toString(16)}"
            ).initCause(e) as ZipException)
        }
    }

    /**
     * "enum" for the possible actions to take if the extra field
     * cannot be parsed.
     *
     *
     * This class has been created long before Java 5 and would
     * have been a real enum ever since.
     */
    class UnparseableExtraField private constructor(private val key: Int) :
        UnparseableExtraFieldBehavior {
        /**
         * Key of the action to take.
         * @return the key
         */
        @Throws(ZipException::class)
        override fun onUnparseableExtraField(
            data: ByteArray, offset: Int, length: Int, local: Boolean, claimedLength: Int
        ): ZipExtraField? {
            return when (key) {
                THROW_KEY -> throw ZipException(
                    "Bad extra field starting at $offset. " +
                            "Block length of $claimedLength bytes exceeds remaining data of ${length - WORD} bytes."
                )
                READ_KEY -> {
                    UnparseableExtraFieldData().apply {
                        if (local)
                            parseFromLocalFileData(data, offset, length)
                        else
                            parseFromCentralDirectoryData(data, offset, length)
                    }
                }
                SKIP_KEY -> null
                else -> throw ZipException("Unknown UnparseableExtraField key: $key")
            }
        }

        companion object {
            /**
             * Key for "throw an exception" action.
             */
            const val THROW_KEY = 0

            /**
             * Key for "skip" action.
             */
            const val SKIP_KEY = 1

            /**
             * Key for "read" action.
             */
            const val READ_KEY = 2

            /**
             * Throw an exception if field cannot be parsed.
             */
            @JvmField
            val THROW = UnparseableExtraField(THROW_KEY)

            /**
             * Skip the extra field entirely and don't make its data
             * available - effectively removing the extra field data.
             */
            @JvmField
            val SKIP = UnparseableExtraField(SKIP_KEY)

            /**
             * Read the extra field data into an instance of [UnparseableExtraField].
             */
            @JvmField
            val READ = UnparseableExtraField(READ_KEY)
        }
    }
}