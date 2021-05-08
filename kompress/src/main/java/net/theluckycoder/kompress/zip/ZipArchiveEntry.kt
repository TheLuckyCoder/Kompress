package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.archivers.ArchiveEntry
import net.theluckycoder.kompress.archivers.EntryStreamOffsets
import net.theluckycoder.kompress.zip.ExtraFieldUtils.UnparseableExtraField
import net.theluckycoder.kompress.zip.ExtraFieldUtils.fillExtraField
import net.theluckycoder.kompress.zip.ExtraFieldUtils.mergeCentralDirectoryData
import net.theluckycoder.kompress.zip.ExtraFieldUtils.mergeLocalFileDataData
import net.theluckycoder.kompress.zip.ExtraFieldUtils.parse
import java.io.File
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipException

/**
 * Extension that adds better handling of extra fields and provides
 * access to the internal and external file attributes.
 *
 * Any extra data that cannot be parsed by the rules above will be
 * consumed as "unparseable" extra data and treated differently by the
 * methods of this class.
 *
 * @NotThreadSafe
 */
@Suppress("unused")
public open class ZipArchiveEntry : ZipEntry, ArchiveEntry, EntryStreamOffsets {

    /**
     * Indicates how the name of this entry has been determined.
     */
    public enum class NameSource {
        /**
         * The name has been read from the archive using the encoding
         * of the archive specified when creating the [ZipArchiveInputStream] or [ZipFile] (defaults to the
         * platform's default encoding).
         */
        NAME,

        /**
         * The name has been read from the archive and the archive
         * specified the EFS flag which indicates the name has been
         * encoded as UTF-8.
         */
        NAME_WITH_EFS_FLAG,

        /**
         * The name has been read from an Unicode Extra Field [UnicodePathExtraField].
         */
        UNICODE_EXTRA_FIELD
    }

    /**
     * Indicates how the comment of this entry has been determined.
     */
    public enum class CommentSource {
        /**
         * The comment has been read from the archive using the encoding
         * of the archive specified when creating the [ ] or [ZipFile] (defaults to the
         * platform's default encoding).
         */
        COMMENT,

        /**
         * The comment has been read from an Unicode Extra Field [UnicodeCommentExtraField].
         */
        UNICODE_EXTRA_FIELD
    }

    /**
     * The [java.util.zip.ZipEntry] base class only supports
     * the compression methods STORED and DEFLATED. We override the
     * field so that any compression methods can be used.
     *
     *
     * The default value -1 means that the method has not been specified.
     *
     * @see [COMPRESS-93](https://issues.apache.org/jira/browse/COMPRESS-93)
     */
    private var method: Int = ZipMethod.UNKNOWN_CODE

    private var size = ArchiveEntry.SIZE_UNKNOWN

    /**
     * Retrieves the internal file attributes.
     *
     *
     * **Note**: [ZipArchiveInputStream] is unable to fill
     * this field, you must use [ZipFile] if you want to read
     * entries using this attribute.
     */
    public var internalAttributes: Int = 0

    /**
     * The "version required to expand" field.
     */
    public var versionRequired: Int = 0

    /**
     * The "version made by" field.
     */
    public var versionMadeBy: Int = 0
    
    /**
     * Platform specification to put into the &quot;version made
     * by&quot; part of the central file header.
     *
     * @return PLATFORM_FAT unless [unixMode] has been set,
     * in which case PLATFORM_UNIX will be returned.
     */
    public var platform: Int = PLATFORM_FAT
        internal set

    /**
     * The content of the flags field.
     */
    public var rawFlag: Int = 0

    /**
     * The external file attributes.
     *
     *
     * **Note**: [ZipArchiveInputStream] is unable to fill
     * this field, you must use [ZipFile] if you want to read
     * entries using this attribute.
     */
    public var externalAttributes: Long = 0

    /**
     * Alignment for this entry.
     *
     * 0 for default.
     */
    internal var alignment: Int = 0
        set(alignment) {
            require(!(alignment and alignment - 1 != 0 || alignment > 0xffff)) {
                "Invalid value for alignment, must be power of two and no bigger than ${0xffff} but is $alignment"
            }
            field = alignment
        }

    /**
     * Looks up extra field data that couldn't be parsed correctly.
     *
     * @return null if no such field exists.
     */
    private var extraFields: Array<ZipExtraField>? = null
    private var unparseableExtra: UnparseableExtraFieldData? = null
    private var _name: String? = null
    private var rawName: ByteArray? = null
    public var generalPurposeBit: GeneralPurposeBit = GeneralPurposeBit()
    internal var localHeaderOffset: Long = EntryStreamOffsets.OFFSET_UNKNOWN

    /**
     * The data offset.
     */
    override var dataOffset: Long = EntryStreamOffsets.OFFSET_UNKNOWN
        internal set

    override var isStreamContiguous: Boolean = false
        internal set

    /**
     * The source of the name field value.
     */
    public var nameSource: NameSource = NameSource.NAME

    /**
     * The source of the comment field value.
     */
    public var commentSource: CommentSource = CommentSource.COMMENT

    /**
     * The number of the split segment this entry starts at.
     */
    public var diskNumberStart: Long = 0

    /**
     * Creates a new zip entry with the specified name.
     *
     *
     * Assumes the entry represents a directory if and only if the
     * name ends with a forward slash "/".
     *
     * @param name the name of the entry
     */
    public constructor(name: String) : super(name) {
        _name = name
    }

    /**
     * Creates a new zip entry with fields taken from the specified zip entry.
     *
     *
     * Assumes the entry represents a directory if and only if the
     * name ends with a forward slash "/".
     *
     * @param entry the entry to get fields from
     * @throws ZipException on error
     */
    public constructor(entry: ZipEntry) : super(entry) {
        _name = entry.name
        val extra = entry.extra
        if (extra != null) {
            setExtraFields(parse(extra, true, ExtraFieldParsingMode.BEST_EFFORT))
        } else {
            // initializes extra data to an empty byte array
            setExtra()
        }
        method = entry.method
        size = entry.size
    }

    /**
     * Creates a new zip entry with fields taken from the specified zip entry.
     *
     *
     * Assumes the entry represents a directory if and only if the
     * name ends with a forward slash "/".
     *
     * @param entry the entry to get fields from
     * @throws ZipException on error
     */
    public constructor(entry: ZipArchiveEntry) : this(entry as ZipEntry) {
        internalAttributes = entry.internalAttributes
        externalAttributes = entry.externalAttributes
        setExtraFields(allExtraFieldsNoCopy)
        platform = entry.platform
        val other = entry.generalPurposeBit
        generalPurposeBit = other.clone() as GeneralPurposeBit
    }

    internal constructor() : this("")

    /**
     * Creates a new zip entry taking some information from the given
     * file and using the provided name.
     *
     *
     * The name will be adjusted to end with a forward slash "/" if
     * the file is a directory.  If the file is not a directory a
     * potential trailing forward slash will be stripped from the
     * entry name.
     * @param inputFile file to create the entry from
     * @param entryName name of the entry
     */
    public constructor(
        inputFile: File,
        entryName: String
    ) : this(if (inputFile.isDirectory && !entryName.endsWith("/")) "$entryName/" else entryName) {
        if (inputFile.isFile) {
            size = inputFile.length()
        }
        time = inputFile.lastModified()
        // TODO are there any other fields we can set here?
    }

    /**
     * Overwrite clone.
     * @return a cloned copy of this ZipArchiveEntry
     */
    override fun clone(): Any {
        val e = super.clone() as ZipArchiveEntry
        e.internalAttributes = internalAttributes
        e.externalAttributes = externalAttributes
        e.setExtraFields(allExtraFieldsNoCopy)
        return e
    }

    /**
     * Returns the compression method of this entry, or -1 if the
     * compression method has not been specified.
     *
     * @return compression method
     */
    override fun getMethod(): Int = method

    /**
     * Sets the compression method of this entry.
     *
     * @param method compression method
     */
    final override fun setMethod(method: Int) {
        require(method >= 0) { "ZIP compression method can not be negative: $method" }
        this.method = method
    }

    /**
     * Unix permission.
     */
    public var unixMode: Int
        get() = if (platform != PLATFORM_UNIX) 0 else (externalAttributes shr SHORT_SHIFT and SHORT_MASK.toLong()).toInt()
        set(mode) {
            // CheckStyle:MagicNumberCheck OFF - no point
            externalAttributes = (mode shl SHORT_SHIFT // MS-DOS read-only attribute
                    or (if (mode and 128 == 0) 1 else 0) // MS-DOS directory flag
                    or if (isDirectory) 0x10 else 0).toLong()
            // CheckStyle:MagicNumberCheck ON
            platform = PLATFORM_UNIX
        }

    /**
     * Returns true if this entry represents a unix symlink,
     * in which case the entry's content contains the target path
     * for the symlink.
     *
     * @return true if the entry represents a unix symlink, false otherwise.
     */
    public val isUnixSymlink: Boolean
        get() = unixMode and UnixStat.FILE_TYPE_FLAG == UnixStat.LINK_FLAG

    /**
     * Replaces all currently attached extra fields with the new array.
     * @param fields an array of extra fields
     */
    public fun setExtraFields(fields: Array<ZipExtraField>?) {
        unparseableExtra = null

        val newFields = mutableListOf<ZipExtraField>()
        fields?.forEach { field ->
            if (field is UnparseableExtraFieldData) {
                unparseableExtra = field
            } else {
                newFields.add(field)
            }
        }

        extraFields = newFields.toTypedArray()
        setExtra()
    }

    /**
     * Retrieves all extra fields that have been parsed successfully.
     *
     *
     * **Note**: The set of extra fields may be incomplete when
     * [ZipArchiveInputStream] has been used as some extra
     * fields use the central directory to store additional
     * information.
     *
     * @return an array of the extra fields
     */
    public fun getExtraFields(): Array<ZipExtraField> = parseableExtraFields

    /**
     * Retrieves extra fields.
     * @param includeUnparseable whether to also return unparseable
     * extra fields as [UnparseableExtraFieldData] if such data
     * exists.
     * @return an array of the extra fields
     */
    public fun getExtraFields(includeUnparseable: Boolean): Array<ZipExtraField> {
        return if (includeUnparseable) allExtraFields else parseableExtraFields
    }

    /**
     * Retrieves extra fields.
     * @param parsingBehavior controls parsing of extra fields.
     * @return an array of the extra fields
     *
     * @throws ZipException if parsing fails, can not happen if `parsingBehavior` is [ExtraFieldParsingMode.BEST_EFFORT].
     */
    @Throws(ZipException::class)
    public fun getExtraFields(parsingBehavior: ExtraFieldParsingBehavior): Array<ZipExtraField> {
        if (parsingBehavior === ExtraFieldParsingMode.BEST_EFFORT)
            return getExtraFields(true)

        if (parsingBehavior === ExtraFieldParsingMode.ONLY_PARSEABLE_LENIENT)
            return getExtraFields(false)

        val local = extra
        val localFields = listOf(*parse(local, true, parsingBehavior))
        val central = centralDirectoryExtra
        val centralFields = mutableListOf(*parse(central, false, parsingBehavior))

        val merged = mutableListOf<ZipExtraField>()
        for (l in localFields) {
            val c: ZipExtraField? = if (l is UnparseableExtraFieldData) {
                findUnparseable(centralFields)
            } else {
                findMatching(l.headerId, centralFields)
            }

            if (c != null) {
                val cd = c.getCentralDirectoryData()
                if (cd != null && cd.isNotEmpty()) {
                    l.parseFromCentralDirectoryData(cd, 0, cd.size)
                }
                centralFields.remove(c)
            }

            merged.add(l)
        }
        merged.addAll(centralFields)

        return merged.toTypedArray()
    }

    private val parseableExtraFieldsNoCopy: Array<ZipExtraField>
        get() = extraFields ?: noExtraFields
    private val parseableExtraFields: Array<ZipExtraField>
        get() {
            val parseableExtraFields = parseableExtraFieldsNoCopy
            return if (parseableExtraFields.contentEquals(extraFields)) parseableExtraFields.copyOf() else parseableExtraFields
        }

    /**
     * Get all extra fields, including unparseable ones.
     * @return An array of all extra fields. Not necessarily a copy of internal data structures, hence private method
     */
    private val allExtraFieldsNoCopy: Array<ZipExtraField>
        get() {
            extraFields?.let {
                if (unparseableExtra != null) mergedFields else extraFields
            }
            return unparseableOnly
        }

    private val mergedFields: Array<ZipExtraField>
        get() {
            val zipExtraFields = extraFields ?: emptyArray()

            unparseableExtra?.let {
                return zipExtraFields + arrayOf<ZipExtraField>(it)
            }

            return zipExtraFields
        }

    private val unparseableOnly: Array<ZipExtraField>
        get() = if (unparseableExtra == null) noExtraFields else arrayOf(unparseableExtra!!)

    private val allExtraFields: Array<ZipExtraField>
        get() {
            val allExtraFieldsNoCopy = allExtraFieldsNoCopy
            return if (allExtraFieldsNoCopy.contentEquals(extraFields))
                allExtraFieldsNoCopy.copyOf() else allExtraFieldsNoCopy
        }

    private fun findUnparseable(fs: List<ZipExtraField>): ZipExtraField? {
        return fs.firstOrNull { it is UnparseableExtraFieldData }
    }

    private fun findMatching(headerId: ZipShort, fs: List<ZipExtraField>): ZipExtraField? {
        return fs.firstOrNull { it.headerId == headerId }
    }

    /**
     * Adds an extra field - replacing an already present extra field
     * of the same type.
     *
     *
     * If no extra field of the same type exists, the field will be
     * added as last field.
     * @param ze an extra field
     */
    public fun addExtraField(ze: ZipExtraField) {
        if (ze is UnparseableExtraFieldData) {
            unparseableExtra = ze
        } else {
            val copyExtraFields = extraFields
            extraFields = if (copyExtraFields == null) {
                arrayOf(ze)
            } else {
                if (getExtraField(ze.headerId) != null)
                    removeExtraField(ze.headerId)

                copyExtraFields + arrayOf(ze)
            }
        }
        setExtra()
    }

    /**
     * Adds an extra field - replacing an already present extra field
     * of the same type.
     *
     *
     * The new extra field will be the first one.
     * @param ze an extra field
     */
    public fun addAsFirstExtraField(ze: ZipExtraField) {
        if (ze is UnparseableExtraFieldData) {
            unparseableExtra = ze
        } else {
            if (getExtraField(ze.headerId) != null)
                removeExtraField(ze.headerId)

            extraFields?.let { copy ->
                extraFields = arrayOf(ze) + copy
            }
        }
        setExtra()
    }

    /**
     * Remove an extra field.
     * @param type the type of extra field to remove
     */
    public fun removeExtraField(type: ZipShort) {
        val copy = extraFields ?: throw NoSuchElementException()

        val newResult = copy.filter { type != it.headerId }

        if (copy.size == newResult.size)
            throw NoSuchElementException()

        extraFields = newResult.toTypedArray()
        setExtra()
    }

    /**
     * Removes unparseable extra field data.
     */
    public fun removeUnparseableExtraFieldData() {
        if (unparseableExtra == null)
            throw NoSuchElementException()

        unparseableExtra = null
        setExtra()
    }

    /**
     * Looks up an extra field by its header id.
     *
     * @param type the header id
     * @return null if no such field exists.
     */
    public fun getExtraField(type: ZipShort): ZipExtraField? {
        extraFields?.let {
            for (extraField in it) {
                if (type == extraField.headerId)
                    return extraField
            }
        }
        return null
    }

    /**
     * Parses the given bytes as extra field data and consumes any
     * unparseable data as an [UnparseableExtraFieldData]
     * instance.
     * @param extra an array of bytes to be parsed into extra fields
     * @throws RuntimeException if the bytes cannot be parsed
     * @throws RuntimeException on error
     */
    @Throws(RuntimeException::class)
    override fun setExtra(extra: ByteArray) {
        val local = parse(extra, true, ExtraFieldParsingMode.BEST_EFFORT)
        mergeExtraFields(local, true)
    }

    /**
     * Unfortunately [java.util.zip.ZipOutputStream] seems to access the extra data
     * directly, so overriding getExtra doesn't help - we need to
     * modify super's data directly.
     */
    internal fun setExtra() {
        super.setExtra(mergeLocalFileDataData(allExtraFieldsNoCopy))
    }

    /**
     * Retrieves the extra data for the local file data.
     * @return the extra data for local file
     */
    public val localFileDataExtra: ByteArray
        get() = extra ?: EMPTY

    /**
     * Retrieves the extra data for the central directory of extra fields.
     * @return the central directory extra data
     */
    public var centralDirectoryExtra: ByteArray
        get() = mergeCentralDirectoryData(allExtraFieldsNoCopy)
        set(value) {
            val central = parse(value, false, ExtraFieldParsingMode.BEST_EFFORT)
            mergeExtraFields(central, false)
        }

    /**
     * Get the name of the entry.
     *
     *
     * This method returns the raw name as it is stored inside of the archive.
     *
     * @return the entry name
     */
    override fun getName(): String = _name ?: super.getName()

    /**
     * Is this entry a directory?
     * @return true if the entry is a directory
     */
    override fun isDirectory(): Boolean = name.endsWith("/")

    /**
     * Set the name of the entry.
     * @param name the name to use
     */
    internal open fun setName(name: String) {
        var newName = name
        if (platform == PLATFORM_FAT && !name.contains("/")) {
            newName = name.replace('\\', '/')
        }
        _name = newName
    }

    /**
     * Gets the uncompressed size of the entry data.
     *
     *
     * **Note**: [ZipArchiveInputStream] may create
     * entries that return [SIZE_UNKNOWN][.SIZE_UNKNOWN] as long
     * as the entry hasn't been read completely.
     *
     * @return the entry size
     */
    override fun getSize(): Long = size

    /**
     * Sets the uncompressed size of the entry data.
     * @param size the uncompressed size in bytes
     * @throws IllegalArgumentException if the specified size is less
     * than 0
     */
    override fun setSize(size: Long) {
        require(size >= 0) { "Invalid entry size" }
        this.size = size
    }

    /**
     * Sets the name using the raw bytes and the string created from
     * it by guessing or using the configured encoding.
     * @param name the name to use created from the raw bytes using
     * the guessed or configured encoding
     * @param rawName the bytes originally read as name from the
     * archive
     */
    internal open fun setName(name: String, rawName: ByteArray?) {
        setName(name)
        this.rawName = rawName
    }

    /**
     * Returns the raw bytes that made up the name before it has been
     * converted using the configured or guessed encoding.
     *
     *
     * This method will return null if this instance has not been
     * read from an archive.
     *
     * @return the raw name bytes
     */
    public fun getRawName(): ByteArray? = rawName?.copyOf()

    /**
     * Get the hashCode of the entry.
     * This uses the name as the hashcode.
     * @return a hashcode.
     */
    override fun hashCode(): Int {
        // this method has severe consequences on performance. We cannot rely
        // on the super.hashCode() method since super.getName() always return
        // the empty string in the current implementation (there's no setter)
        // so it is basically draining the performance of a HashMap lookup
        return name.hashCode()
    }

    /**
     * If there are no extra fields, use the given fields as new extra
     * data - otherwise merge the fields assuming the existing fields
     * and the new fields stem from different locations inside the
     * archive.
     * @param f the extra fields to merge
     * @param local whether the new fields originate from local data
     */
    private fun mergeExtraFields(f: Array<ZipExtraField>, local: Boolean) {
        if (extraFields == null) {
            setExtraFields(f)
        } else {
            for (element in f) {
                val existing: ZipExtraField? = if (element is UnparseableExtraFieldData) {
                    unparseableExtra
                } else {
                    getExtraField(element.headerId)
                }
                if (existing == null) {
                    addExtraField(element)
                } else {
                    val b =
                        if (local) element.getLocalFileDataData() else element.getCentralDirectoryData()
                    try {
                        if (local) {
                            existing.parseFromLocalFileData(b!!, 0, b.size)
                        } else {
                            existing.parseFromCentralDirectoryData(b!!, 0, b.size)
                        }
                    } catch (ex: ZipException) {
                        // emulate ExtraFieldParsingMode.fillAndMakeUnrecognizedOnError
                        val u = UnrecognizedExtraField(existing.headerId)
                        if (local) {
                            u.setLocalFileDataData(b)
                            u.setCentralDirectoryData(existing.getCentralDirectoryData())
                        } else {
                            u.setLocalFileDataData(existing.getLocalFileDataData())
                            u.setCentralDirectoryData(b)
                        }
                        removeExtraField(existing.headerId)
                        addExtraField(u)
                    }
                }
            }
            setExtra()
        }
    }

    /**
     * Wraps [java.util.zip.ZipEntry.getTime] with a [Date] as the
     * entry's last modified date.
     *
     *
     * Changes to the implementation of [java.util.zip.ZipEntry.getTime]
     * leak through and the returned value may depend on your local
     * time zone as well as your version of Java.
     */
    public override fun getLastModifiedDate(): Date = Date(time)

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null || javaClass != other.javaClass) {
            return false
        }
        val entry = other as ZipArchiveEntry
        val myName = name
        val otherName = entry.name
        if (myName != otherName) {
            return false
        }
        var myComment = comment
        var otherComment = entry.comment
        if (myComment == null) {
            myComment = ""
        }
        if (otherComment == null) {
            otherComment = ""
        }
        return (time == entry.time &&
                myComment == otherComment &&
                internalAttributes == entry.internalAttributes &&
                platform == entry.platform &&
                externalAttributes == entry.externalAttributes &&
                method == entry.method &&
                size == entry.size &&
                crc == entry.crc &&
                compressedSize == entry.compressedSize &&
                centralDirectoryExtra.contentEquals(entry.centralDirectoryExtra) &&
                localFileDataExtra.contentEquals(entry.localFileDataExtra) &&
                localHeaderOffset == entry.localHeaderOffset &&
                dataOffset == entry.dataOffset &&
                generalPurposeBit == entry.generalPurposeBit)
    }

    /**
     * How to try to parse the extra fields.
     *
     *
     * Configures the behavior for:
     *
     *  * What shall happen if the extra field content doesn't
     * follow the recommended pattern of two-byte id followed by a
     * two-byte length?
     *  * What shall happen if an extra field is generally supported
     * by Commons Compress but its content cannot be parsed
     * correctly? This may for example happen if the archive is
     * corrupt, it triggers a bug in Commons Compress or the extra
     * field uses a version not (yet) supported by Commons
     * Compress.
     *
     */
    public enum class ExtraFieldParsingMode(private val onUnparseableData: UnparseableExtraField) :
        ExtraFieldParsingBehavior {
        /**
         * Try to parse as many extra fields as possible and wrap
         * unknown extra fields as well as supported extra fields that
         * cannot be parsed in [UnrecognizedExtraField].
         *
         *
         * Wrap extra data that doesn't follow the recommended
         * pattern in an [UnparseableExtraFieldData]
         * instance.
         *
         *
         * This is the default behavior starting with Commons Compress 1.19.
         */
        BEST_EFFORT(UnparseableExtraField.READ) {
            override fun fill(
                field: ZipExtraField,
                data: ByteArray,
                offset: Int,
                length: Int,
                local: Boolean
            ): ZipExtraField {
                return fillAndMakeUnrecognizedOnError(field, data, offset, length, local)
            }
        },

        /**
         * Try to parse as many extra fields as possible and wrap
         * unknown extra fields in [UnrecognizedExtraField].
         *
         *
         * Wrap extra data that doesn't follow the recommended
         * pattern in an [UnparseableExtraFieldData]
         * instance.
         *
         *
         * Throw an exception if an extra field that is generally
         * supported cannot be parsed.
         *
         *
         * This used to be the default behavior prior to Commons
         * Compress 1.19.
         */
        STRICT_FOR_KNOW_EXTRA_FIELDS(UnparseableExtraField.READ),

        /**
         * Try to parse as many extra fields as possible and wrap
         * unknown extra fields as well as supported extra fields that
         * cannot be parsed in [UnrecognizedExtraField].
         *
         *
         * Ignore extra data that doesn't follow the recommended
         * pattern.
         */
        ONLY_PARSEABLE_LENIENT(UnparseableExtraField.SKIP) {
            override fun fill(
                field: ZipExtraField,
                data: ByteArray,
                offset: Int,
                length: Int,
                local: Boolean
            ): ZipExtraField {
                return fillAndMakeUnrecognizedOnError(field, data, offset, length, local)
            }
        },

        /**
         * Try to parse as many extra fields as possible and wrap
         * unknown extra fields in [UnrecognizedExtraField].
         *
         *
         * Ignore extra data that doesn't follow the recommended
         * pattern.
         *
         *
         * Throw an exception if an extra field that is generally
         * supported cannot be parsed.
         */
        ONLY_PARSEABLE_STRICT(UnparseableExtraField.SKIP),

        /**
         * Throw an exception if any of the recognized extra fields
         * cannot be parsed or any extra field violates the
         * recommended pattern.
         */
        DRACONIC(UnparseableExtraField.THROW);

        @Throws(ZipException::class)
        override fun onUnparseableExtraField(
            data: ByteArray, offset: Int, length: Int, local: Boolean,
            claimedLength: Int
        ): ZipExtraField? {
            return onUnparseableData.onUnparseableExtraField(
                data,
                offset,
                length,
                local,
                claimedLength
            )
        }

        @Throws(InstantiationException::class, IllegalAccessException::class)
        override fun createExtraField(headerId: ZipShort): ZipExtraField {
            return ExtraFieldUtils.createExtraField(headerId)
        }

        @Throws(ZipException::class)
        override fun fill(
            field: ZipExtraField,
            data: ByteArray,
            offset: Int,
            length: Int,
            local: Boolean
        ): ZipExtraField {
            return fillExtraField(field, data, offset, length, local)
        }

        public companion object {
            private fun fillAndMakeUnrecognizedOnError(
                field: ZipExtraField, data: ByteArray, offset: Int,
                length: Int, local: Boolean
            ): ZipExtraField {
                return try {
                    fillExtraField(field, data, offset, length, local)
                } catch (ex: ZipException) {
                    val u = UnrecognizedExtraField(field.headerId)
                    if (local)
                        u.setLocalFileDataData(data.copyOfRange(offset, offset + length))
                    else
                        u.setCentralDirectoryData(data.copyOfRange(offset, offset + length))
                    u
                }
            }
        }
    }

    public companion object {
        public const val PLATFORM_UNIX: Int = 3
        public const val PLATFORM_FAT: Int = 0
        public const val CRC_UNKNOWN: Int = -1
        private const val SHORT_MASK = 0xFFFF
        private const val SHORT_SHIFT = 16
        private val EMPTY = ByteArray(0)
        private val noExtraFields = emptyArray<ZipExtraField>()
    }
}
