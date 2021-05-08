package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.archivers.EntryStreamOffsets
import net.theluckycoder.kompress.utils.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.*
import java.util.zip.Inflater
import java.util.zip.ZipException
import kotlin.math.max

/**
 * Replacement for [java.util.zip.ZipFile].
 *
 *
 * This class adds support for file name encodings other than UTF-8
 * (which is required to work on ZIP files created by native zip tools
 * and is able to skip a preamble like the one found in self
 * extracting archives.
 *
 * It doesn't extend [java.util.zip.ZipFile] as it would
 * have to reimplement all methods anyway.  Like
 * [java.util.zip.ZipFile], it uses [FileChannel] under the
 * covers and supports compressed and uncompressed entries.
 * It also supports Zip64 extensions and thus individual entries
 * and archives larger than 4 GB or with more than 65536 entries.
 *
 *
 * The method signatures mimic the ones of
 * `java.util.zip.ZipFile`, with a couple of exceptions:
 *
 *
 *  * There is no getName method.
 *  * entries has been renamed to getEntries.
 *  * getEntries and getEntry return [ZipArchiveEntry] instances.
 *  * close is allowed to throw IOException.
 *
 */
public class ZipFile private constructor(
    private val archive: FileChannel,
    private val archiveName: String,
    /**
     * The encoding to use for file names and the file comment.
     *
     *
     * For a list of possible values see [http://java.sun.com/j2se/1.5.0/docs/guide/intl/encoding.doc.html](http://java.sun.com/j2se/1.5.0/docs/guide/intl/encoding.doc.html).
     * Defaults to UTF-8.
     */
    public val encoding: String?,
    /**
     * Whether to look for and use Unicode extra fields.
     */
    private val useUnicodeExtraFields: Boolean,
    closeOnError: Boolean, ignoreLocalFileHeader: Boolean
) : Closeable {
    /**
     * List of entries in the order they appear inside the central
     * directory.
     */
    private val entries: MutableList<ZipArchiveEntry> = LinkedList()

    /**
     * Maps String to list of ZipArchiveEntries, name -> actual entries.
     */
    private val nameMap: MutableMap<String, LinkedList<ZipArchiveEntry>> = HashMap(HASH_SIZE)

    /**
     * The zip encoding to use for file names and the file comment.
     */
    private val zipEncoding: ZipEncoding = ZipEncodingHelper.getZipEncoding(encoding)

    /**
     * Whether the file is closed.
     */
    @Volatile
    private var closed = true

    /**
     * Whether the zip archive is a split zip archive
     */
    private val isSplitZipArchive: Boolean = archive is ZipSplitReadOnlyFileChannel

    // cached buffers - must only be used locally in the class (COMPRESS-172 - reduce garbage collection)
    private val dwordBuf = ByteArray(ZipConstants.DWORD)
    private val wordBuf = ByteArray(ZipConstants.WORD)
    private val cfhBuf = ByteArray(CFH_LEN)
    private val shortBuf = ByteArray(ZipConstants.SHORT)
    private val dwordBbuf = ByteBuffer.wrap(dwordBuf)
    private val wordBbuf = ByteBuffer.wrap(wordBuf)
    private val cfhBbuf = ByteBuffer.wrap(cfhBuf)
    private val shortBbuf = ByteBuffer.wrap(shortBuf)

    /**
     * Opens the given file for reading, assuming the specified
     * encoding for file names, scanning unicode extra fields.
     *
     * @param name name of the archive.
     * @param encoding the encoding to use for file names, use null
     * for the platform's default encoding
     *
     * @throws IOException if an error occurs while reading the file.
     */
    public constructor(name: String, encoding: String?) : this(File(name), encoding, true)

    /**
     * Opens the given file for reading, assuming the specified
     * encoding for file names.
     *
     *
     *
     * By default the central directory record and all local file headers of the archive will be read immediately
     * which may take a considerable amount of time when the archive is big. The `ignoreLocalFileHeader` parameter
     * can be set to `true` which restricts parsing to the central directory. Unfortunately the local file header
     * may contain information not present inside of the central directory which will not be available when the argument
     * is set to `true`. This includes the content of the Unicode extra field, so setting `ignoreLocalFileHeader` to `true` means `useUnicodeExtraFields` will be ignored effectively. Also
     * [getRawInputStream] is always going to return `null` if `ignoreLocalFileHeader` is `true`.
     *
     * @param file the archive.
     * @param encoding the encoding to use for file names, use null
     * for the platform's default encoding
     * @param useUnicodeExtraFields whether to use InfoZIP Unicode
     * Extra Fields (if present) to set the file names.
     * @param ignoreLocalFileHeader whether to ignore information
     * stored inside the local file header (see the notes in this method's javadoc)
     *
     * @throws IOException if an error occurs while reading the file.
     */
    @JvmOverloads
    public constructor(
        file: File, encoding: String? = ZipEncodingHelper.UTF8,
        useUnicodeExtraFields: Boolean = true, ignoreLocalFileHeader: Boolean = false
    ) : this(
        file.inputFileChannel(),
        file.absolutePath, encoding, useUnicodeExtraFields, true, ignoreLocalFileHeader
    )

    /**
     * Opens the given channel for reading, assuming the specified
     * encoding for file names.
     *
     *
     * allows you to read from an in-memory archive.
     *
     * @param channel the archive.
     * @param archiveName name of the archive, used for error messages only.
     * @param encoding the encoding to use for file names, use null
     * for the platform's default encoding
     * @param useUnicodeExtraFields whether to use InfoZIP Unicode
     * Extra Fields (if present) to set the file names.
     *
     * @throws IOException if an error occurs while reading the file.
     */
    @JvmOverloads
    public constructor(
        channel: FileChannel, archiveName: String = "unknown archive",
        encoding: String? = ZipEncodingHelper.UTF8, useUnicodeExtraFields: Boolean = true
    ) : this(channel, archiveName, encoding, useUnicodeExtraFields, false, false)

    /**
     * Opens the given channel for reading, assuming the specified
     * encoding for file names.
     *
     * By default the central directory record and all local file headers of the archive will be read immediately
     * which may take a considerable amount of time when the archive is big. The `ignoreLocalFileHeader` parameter
     * can be set to `true` which restricts parsing to the central directory. Unfortunately the local file header
     * may contain information not present inside of the central directory which will not be available when the argument
     * is set to `true`. This includes the content of the Unicode extra field, so setting `ignoreLocalFileHeader` to `true` means `useUnicodeExtraFields` will be ignored effectively. Also
     * [getRawInputStream] is always going to return `null` if `ignoreLocalFileHeader` is `true`.
     *
     * @param channel the archive.
     * @param archiveName name of the archive, used for error messages only.
     * @param encoding the encoding to use for file names, use null
     * for the platform's default encoding
     * @param useUnicodeExtraFields whether to use InfoZIP Unicode
     * Extra Fields (if present) to set the file names.
     * @param ignoreLocalFileHeader whether to ignore information
     * stored inside the local file header (see the notes in this method's javadoc)
     *
     * @throws IOException if an error occurs while reading the file.
     */
    public constructor(
        channel: FileChannel, archiveName: String,
        encoding: String?, useUnicodeExtraFields: Boolean,
        ignoreLocalFileHeader: Boolean
    ) : this(channel, archiveName, encoding, useUnicodeExtraFields, false, ignoreLocalFileHeader)

    /**
     * Closes the archive.
     * @throws IOException if an error occurs closing the archive.
     */
    @Throws(IOException::class)
    override fun close() {
        // this flag is only written here and read in finalize() which
        // can never be run in parallel.
        // no synchronization needed.
        closed = true
        archive.close()
    }

    /**
     * Returns all entries.
     *
     *
     * Entries will be returned in the same order they appear
     * within the archive's central directory.
     *
     * @return all entries as [ZipArchiveEntry] instances
     */
    public fun getEntries(): Enumeration<ZipArchiveEntry> = Collections.enumeration(entries)

    /**
     * Returns all entries in physical order.
     *
     *
     * Entries will be returned in the same order their contents
     * appear within the archive.
     *
     * @return all entries as [ZipArchiveEntry] instances
     */
    public fun getEntriesInPhysicalOrder(): Enumeration<ZipArchiveEntry> {
        val allEntries = entries.toMutableList()
        allEntries.sortWith(offsetComparator)
        return Collections.enumeration(allEntries)
    }

    /**
     * Returns a named entry - or `null` if no entry by
     * that name exists.
     *
     *
     * If multiple entries with the same name exist the first entry
     * in the archive's central directory by that name is
     * returned.
     *
     * @param name name of the entry.
     * @return the ZipArchiveEntry corresponding to the given name - or
     * `null` if not present.
     */
    public fun getEntry(name: String): ZipArchiveEntry? {
        val entriesOfThatName = nameMap[name]
        return entriesOfThatName?.first
    }

    /**
     * Returns all named entries in the same order they appear within
     * the archive's central directory.
     *
     * @param name name of the entry.
     * @return the Iterable&lt;ZipArchiveEntry&gt; corresponding to the
     * given name
     */
    public fun getEntries(name: String): Iterable<ZipArchiveEntry> {
        val entriesOfThatName: List<ZipArchiveEntry>? = nameMap[name]
        return entriesOfThatName ?: emptyList()
    }

    /**
     * Returns all named entries in the same order their contents
     * appear within the archive.
     *
     * @param name name of the entry.
     * @return the Iterable&lt;ZipArchiveEntry&gt; corresponding to the
     * given name
     */
    public fun getEntriesInPhysicalOrder(name: String): Iterable<ZipArchiveEntry> {
        var entriesOfThatName = emptyArray<ZipArchiveEntry>()
        if (nameMap.containsKey(name)) {
            entriesOfThatName = nameMap[name]!!.toArray(entriesOfThatName)
            Arrays.sort(entriesOfThatName, offsetComparator)
        }
        return entriesOfThatName.asSequence().asIterable()
    }

    /**
     * Whether this class is able to read the given entry.
     *
     *
     * May return false if it is set up to use encryption or a
     * compression method that hasn't been implemented yet.
     * @param ze the entry
     * @return whether this class is able to read the given entry.
     */
    public fun canReadEntryData(ze: ZipArchiveEntry): Boolean = ZipUtil.canHandleEntryData(ze)

    /**
     * Expose the raw stream of the archive entry (compressed form).
     *
     *
     * This method does not relate to how/if we understand the payload in the
     * stream, since we really only intend to move it on to somewhere else.
     *
     * @param ze The entry to get the stream for
     * @return The raw input stream containing (possibly) compressed data.
     */
    public fun getRawInputStream(ze: ZipArchiveEntry): InputStream? {
        if (ze !is Entry)
            return null

        val start = ze.dataOffset
        return if (start == EntryStreamOffsets.OFFSET_UNKNOWN) {
            null
        } else createBoundedInputStream(
            start,
            ze.getCompressedSize()
        )
    }

    /**
     * Transfer selected entries from this zip file to a given #ZipArchiveOutputStream.
     * Compression and all other attributes will be as in this file.
     *
     * This method transfers entries based on the central directory of the zip file.
     *
     * @param target The zipArchiveOutputStream to write the entries to
     * @param predicate A predicate that selects which entries to write
     * @throws IOException on error
     */
    @Throws(IOException::class)
    public fun copyRawEntries(
        target: ZipArchiveOutputStream,
        predicate: (ZipArchiveEntry) -> Boolean
    ) {
        getEntriesInPhysicalOrder().asSequence()
            .filter(predicate)
            .forEach { target.addRawArchiveEntry(it, getRawInputStream(it)!!) }
    }

    /**
     * Returns an InputStream for reading the contents of the given entry.
     *
     * @param ze the entry to get the stream for.
     * @return a stream to read the entry from. The returned stream
     * implements [InputStreamStatistics].
     * @throws IOException if unable to create an input stream from the [ZipArchiveEntry]
     */
    @Throws(IOException::class)
    public fun getInputStream(ze: ZipArchiveEntry): InputStream? {
        if (ze !is Entry) {
            return null
        }
        // cast validity is checked just above
        ZipUtil.checkRequestedFeatures(ze)
        val start = getDataOffset(ze)

        // doesn't get closed if the method is not supported - which
        // should never happen because of the checkRequestedFeatures
        // call above
        val input: InputStream =
            BufferedInputStream(createBoundedInputStream(start, ze.getCompressedSize()))
        return when (ZipMethod.getMethodByCode(ze.getMethod())) {
            ZipMethod.STORED -> StoredStatisticsStream(input)
            ZipMethod.UNSHRINKING -> UnshrinkingInputStream(input)
            ZipMethod.IMPLODING -> ExplodingInputStream(
                ze.generalPurposeBit.slidingDictionarySize,
                ze.generalPurposeBit.numberOfShannonFanoTrees, input
            )
            ZipMethod.DEFLATED -> {
                val inflater = Inflater(true)
                // Inflater with nowrap=true has this odd contract for a zero padding
                // byte following the data stream; this used to be zlib's requirement
                // and has been fixed a long time ago, but the contract persists so
                // we comply.
                // https://docs.oracle.com/javase/7/docs/api/java/util/zip/Inflater.html#Inflater(boolean)
                object : InflaterInputStreamWithStatistics(
                    SequenceInputStream(input, ByteArrayInputStream(ONE_ZERO_BYTE)),
                    inflater
                ) {
                    @Throws(IOException::class)
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            inflater.end()
                        }
                    }
                }
            }
            ZipMethod.BZIP2, ZipMethod.ENHANCED_DEFLATED, ZipMethod.AES_ENCRYPTED, ZipMethod.EXPANDING_LEVEL_1,
            ZipMethod.EXPANDING_LEVEL_2, ZipMethod.EXPANDING_LEVEL_3, ZipMethod.EXPANDING_LEVEL_4, ZipMethod.JPEG,
            ZipMethod.LZMA, ZipMethod.PKWARE_IMPLODING, ZipMethod.PPMD, ZipMethod.TOKENIZATION, ZipMethod.WAVPACK,
            ZipMethod.XZ, ZipMethod.UNKNOWN ->
                throw UnsupportedZipFeatureException(
                    ZipMethod.getMethodByCode(ze.getMethod()),
                    ze
                )
        }
    }

    /**
     *
     *
     * Convenience method to return the entry's content as a String if isUnixSymlink()
     * returns true for it, otherwise returns null.
     *
     *
     *
     * This method assumes the symbolic link's file name uses the
     * same encoding that as been specified for this ZipFile.
     *
     * @param entry ZipArchiveEntry object that represents the symbolic link
     * @return entry's content as a String
     * @throws IOException problem with content's input stream
     */
    @Throws(IOException::class)
    public fun getUnixSymlink(entry: ZipArchiveEntry?): String? {
        if (entry != null && entry.isUnixSymlink) {
            getInputStream(entry)?.use { inputStream ->
                return zipEncoding.decode(inputStream.readBytes())
            }
        }
        return null
    }

    /**
     * Ensures that the close method of this zip file is called when
     * there are no more references to it.
     * @see .close
     */
    @Throws(Throwable::class)
    protected fun finalize() {
        try {
            if (!closed) {
                System.err.println("Cleaning up unclosed ZipFile for archive $archiveName")
                close()
            }
        } finally {
            // TODO super.finalize()
        }
    }

    /**
     * Reads the central directory of the given archive and populates
     * the internal tables with ZipArchiveEntry instances.
     *
     *
     * The ZipArchiveEntries will know all data that can be obtained from
     * the central directory alone, but not the data that requires the
     * local file header or additional data to be read.
     *
     * @return a map of zip entries that didn't have the language
     * encoding flag set when read.
     */
    @Throws(IOException::class)
    private fun populateFromCentralDirectory(): Map<ZipArchiveEntry, NameAndComment> {
        val noUTF8Flag = HashMap<ZipArchiveEntry, NameAndComment>()
        positionAtCentralDirectory()
        wordBbuf.rewind()
        archive.readFully(wordBbuf)
        var sig = ZipLong.getValue(wordBuf)
        if (sig != CFH_SIG && startsWithLocalFileHeader()) {
            throw IOException(
                "Central directory is empty, can't expand"
                        + " corrupt archive."
            )
        }
        while (sig == CFH_SIG) {
            readCentralDirectoryEntry(noUTF8Flag)
            wordBbuf.rewind()
            archive.readFully(wordBbuf)
            sig = ZipLong.getValue(wordBuf)
        }
        return noUTF8Flag
    }

    /**
     * Reads an individual entry of the central directory, creates an
     * ZipArchiveEntry from it and adds it to the global maps.
     *
     * @param noUTF8Flag map used to collect entries that don't have
     * their UTF-8 flag set and whose name will be set by data read
     * from the local file header later.  The current entry may be
     * added to this map.
     */
    @Throws(IOException::class)
    private fun readCentralDirectoryEntry(noUTF8Flag: MutableMap<ZipArchiveEntry, NameAndComment>) {
        cfhBbuf.rewind()
        archive.readFully(cfhBbuf)
        var off = 0
        val ze = Entry()
        val versionMadeBy = ZipShort.getValue(cfhBuf, off)
        off += ZipConstants.SHORT
        ze.versionMadeBy = versionMadeBy
        ze.platform =
            (versionMadeBy shr BYTE_SHIFT) and NIBLET_MASK
        ze.versionRequired = ZipShort.getValue(cfhBuf, off)
        off += ZipConstants.SHORT // version required
        val gpFlag = GeneralPurposeBit.parse(cfhBuf, off)
        val hasUTF8Flag = gpFlag.usesUTF8ForNames()
        val entryEncoding = if (hasUTF8Flag) ZipEncodingHelper.UTF8_ZIP_ENCODING else zipEncoding
        if (hasUTF8Flag) {
            ze.nameSource = ZipArchiveEntry.NameSource.NAME_WITH_EFS_FLAG
        }
        ze.generalPurposeBit = gpFlag
        ze.rawFlag = ZipShort.getValue(cfhBuf, off)
        off += ZipConstants.SHORT
        ze.method = ZipShort.getValue(cfhBuf, off)
        off += ZipConstants.SHORT
        val time = ZipUtil.dosToJavaTime(ZipLong.getValue(cfhBuf, off))
        ze.time = time
        off += ZipConstants.WORD
        ze.crc = ZipLong.getValue(cfhBuf, off)
        off += ZipConstants.WORD
        ze.compressedSize = ZipLong.getValue(cfhBuf, off)
        off += ZipConstants.WORD
        ze.size = ZipLong.getValue(cfhBuf, off)
        off += ZipConstants.WORD
        val fileNameLen = ZipShort.getValue(cfhBuf, off)
        off += ZipConstants.SHORT
        val extraLen = ZipShort.getValue(cfhBuf, off)
        off += ZipConstants.SHORT
        val commentLen = ZipShort.getValue(cfhBuf, off)
        off += ZipConstants.SHORT
        ze.diskNumberStart = ZipShort.getValue(cfhBuf, off).toLong()
        off += ZipConstants.SHORT
        ze.internalAttributes = ZipShort.getValue(cfhBuf, off)
        off += ZipConstants.SHORT
        ze.externalAttributes = ZipLong.getValue(cfhBuf, off)
        off += ZipConstants.WORD
        val fileName = ByteArray(fileNameLen)
        archive.readFully(ByteBuffer.wrap(fileName))
        ze.setName(entryEncoding.decode(fileName), fileName)

        // LFH offset,
        ze.localHeaderOffset = ZipLong.getValue(cfhBuf, off)
        // data offset will be filled later
        entries.add(ze)
        val cdExtraData = ByteArray(extraLen)
        archive.readFully(ByteBuffer.wrap(cdExtraData))
        ze.centralDirectoryExtra = cdExtraData
        setSizesAndOffsetFromZip64Extra(ze)
        val comment = ByteArray(commentLen)
        archive.readFully(ByteBuffer.wrap(comment))
        ze.comment = entryEncoding.decode(comment)
        if (!hasUTF8Flag && useUnicodeExtraFields) {
            noUTF8Flag[ze] = NameAndComment(fileName, comment)
        }
        ze.isStreamContiguous = true
    }

    /**
     * If the entry holds a Zip64 extended information extra field,
     * read sizes from there if the entry's sizes are set to
     * 0xFFFFFFFFF, do the same for the offset of the local file
     * header.
     *
     *
     * Ensures the Zip64 extra either knows both compressed and
     * uncompressed size or neither of both as the internal logic in
     * ExtraFieldUtils forces the field to create local header data
     * even if they are never used - and here a field with only one
     * size would be invalid.
     */
    @Throws(IOException::class)
    private fun setSizesAndOffsetFromZip64Extra(ze: ZipArchiveEntry) {
        val z64 =
            ze.getExtraField(Zip64ExtendedInformationExtraField.HEADER_ID) as? Zip64ExtendedInformationExtraField
        if (z64 != null) {
            val hasUncompressedSize = ze.size == ZipConstants.ZIP64_MAGIC
            val hasCompressedSize = ze.compressedSize == ZipConstants.ZIP64_MAGIC
            val hasRelativeHeaderOffset = ze.localHeaderOffset == ZipConstants.ZIP64_MAGIC
            val hasDiskStart = ze.diskNumberStart == ZipConstants.ZIP64_MAGIC_SHORT.toLong()
            z64.reparseCentralDirectoryData(
                hasUncompressedSize,
                hasCompressedSize,
                hasRelativeHeaderOffset,
                hasDiskStart
            )
            if (hasUncompressedSize) {
                ze.size = z64.size!!.longValue
            } else if (hasCompressedSize) {
                z64.size = ZipEightByteInteger(ze.size)
            }
            if (hasCompressedSize) {
                ze.compressedSize = z64.compressedSize!!.longValue
            } else if (hasUncompressedSize) {
                z64.compressedSize = ZipEightByteInteger(ze.compressedSize)
            }
            if (hasRelativeHeaderOffset) {
                ze.localHeaderOffset = z64.relativeHeaderOffset!!.longValue
            }
            if (hasDiskStart) {
                ze.diskNumberStart = z64.diskStartNumber!!.value
            }
        }
    }

    /**
     * Searches for either the &quot;Zip64 end of central directory
     * locator&quot; or the &quot;End of central dir record&quot;, parses
     * it and positions the stream at the first central directory
     * record.
     */
    @Throws(IOException::class)
    private fun positionAtCentralDirectory() {
        positionAtEndOfCentralDirectoryRecord()
        var found = false
        val searchedForZip64EOCD = archive.position() > ZIP64_EOCDL_LENGTH
        if (searchedForZip64EOCD) {
            archive.position(archive.position() - ZIP64_EOCDL_LENGTH)
            wordBbuf.rewind()
            archive.readFully(wordBbuf)
            found = ZipArchiveOutputStream.ZIP64_EOCD_LOC_SIG.contentEquals(wordBuf)
        }
        if (!found) {
            // not a ZIP64 archive
            if (searchedForZip64EOCD) {
                skipBytes(ZIP64_EOCDL_LENGTH - ZipConstants.WORD)
            }
            positionAtCentralDirectory32()
        } else {
            positionAtCentralDirectory64()
        }
    }

    /**
     * Parses the &quot;Zip64 end of central directory locator&quot;,
     * finds the &quot;Zip64 end of central directory record&quot; using the
     * parsed information, parses that and positions the stream at the
     * first central directory record.
     *
     * Expects stream to be positioned right behind the &quot;Zip64
     * end of central directory locator&quot;'s signature.
     */
    @Throws(IOException::class)
    private fun positionAtCentralDirectory64() {
        if (isSplitZipArchive) {
            wordBbuf.rewind()
            archive.readFully(wordBbuf)
            val diskNumberOfEOCD = ZipLong.getValue(wordBuf)
            dwordBbuf.rewind()
            archive.readFully(dwordBbuf)
            val relativeOffsetOfEOCD = ZipEightByteInteger.getLongValue(dwordBuf)
            (archive as ZipSplitReadOnlyFileChannel)
                .position(diskNumberOfEOCD, relativeOffsetOfEOCD)
        } else {
            skipBytes(
                ZIP64_EOCDL_LOCATOR_OFFSET - ZipConstants.WORD /* signature has already been read */
            )
            dwordBbuf.rewind()
            archive.readFully(dwordBbuf)
            archive.position(ZipEightByteInteger.getLongValue(dwordBuf))
        }
        wordBbuf.rewind()
        archive.readFully(wordBbuf)
        if (!wordBuf.contentEquals(ZipArchiveOutputStream.ZIP64_EOCD_SIG)) {
            throw ZipException(
                "Archive's ZIP64 end of central directory locator is corrupt."
            )
        }
        if (isSplitZipArchive) {
            skipBytes(
                ZIP64_EOCD_CFD_DISK_OFFSET - ZipConstants.WORD /* signature has already been read */
            )
            wordBbuf.rewind()
            archive.readFully(wordBbuf)
            val diskNumberOfCFD = ZipLong.getValue(wordBuf)
            skipBytes(ZIP64_EOCD_CFD_LOCATOR_RELATIVE_OFFSET)
            dwordBbuf.rewind()
            archive.readFully(dwordBbuf)
            val relativeOffsetOfCFD = ZipEightByteInteger.getLongValue(dwordBuf)
            (archive as ZipSplitReadOnlyFileChannel)
                .position(diskNumberOfCFD, relativeOffsetOfCFD)
        } else {
            skipBytes(
                ZIP64_EOCD_CFD_LOCATOR_OFFSET - ZipConstants.WORD /* signature has already been read */
            )
            dwordBbuf.rewind()
            archive.readFully(dwordBbuf)
            archive.position(ZipEightByteInteger.getLongValue(dwordBuf))
        }
    }

    /**
     * Parses the &quot;End of central dir record&quot; and positions
     * the stream at the first central directory record.
     *
     * Expects stream to be positioned at the beginning of the
     * &quot;End of central dir record&quot;.
     */
    @Throws(IOException::class)
    private fun positionAtCentralDirectory32() {
        if (isSplitZipArchive) {
            skipBytes(CFD_DISK_OFFSET)
            shortBbuf.rewind()
            archive.readFully(shortBbuf)
            val diskNumberOfCFD = ZipShort.getValue(shortBuf)
            skipBytes(CFD_LOCATOR_RELATIVE_OFFSET)
            wordBbuf.rewind()
            archive.readFully(wordBbuf)
            val relativeOffsetOfCFD = ZipLong.getValue(wordBuf)
            (archive as ZipSplitReadOnlyFileChannel)
                .position(diskNumberOfCFD.toLong(), relativeOffsetOfCFD)
        } else {
            skipBytes(CFD_LOCATOR_OFFSET)
            wordBbuf.rewind()
            archive.readFully(wordBbuf)
            archive.position(ZipLong.getValue(wordBuf))
        }
    }

    /**
     * Searches for the and positions the stream at the start of the
     * &quot;End of central dir record&quot;.
     */
    @Throws(IOException::class)
    private fun positionAtEndOfCentralDirectoryRecord() {
        val found = tryToLocateSignature(
            MIN_EOCD_SIZE.toLong(), MAX_EOCD_SIZE.toLong(),
            ZipArchiveOutputStream.EOCD_SIG
        )
        if (!found) {
            throw ZipException("Archive is not a ZIP archive")
        }
    }

    /**
     * Searches the archive backwards from minDistance to maxDistance
     * for the given signature, positions the RandomAccessFile right
     * at the signature if it has been found.
     */
    @Throws(IOException::class)
    private fun tryToLocateSignature(
        minDistanceFromEnd: Long,
        maxDistanceFromEnd: Long,
        sig: ByteArray
    ): Boolean {
        var found = false
        var off = archive.size() - minDistanceFromEnd
        val stopSearching = max(0L, archive.size() - maxDistanceFromEnd)
        if (off >= 0) {
            while (off >= stopSearching) {
                archive.position(off)
                try {
                    wordBbuf.rewind()
                    archive.readFully(wordBbuf)
                    wordBbuf.flip()
                } catch (ex: EOFException) {
                    break
                }
                var curr = wordBbuf.get()
                if (curr == sig[POS_0]) {
                    curr = wordBbuf.get()
                    if (curr == sig[POS_1]) {
                        curr = wordBbuf.get()
                        if (curr == sig[POS_2]) {
                            curr = wordBbuf.get()
                            if (curr == sig[POS_3]) {
                                found = true
                                break
                            }
                        }
                    }
                }
                off--
            }
        }
        if (found) {
            archive.position(off)
        }
        return found
    }

    /**
     * Skips the given number of bytes or throws an EOFException if
     * skipping failed.
     */
    @Throws(IOException::class)
    private fun skipBytes(count: Int) {
        val currentPosition = archive.position()
        val newPosition = currentPosition + count
        if (newPosition > archive.size()) {
            throw EOFException()
        }
        archive.position(newPosition)
    }

    /**
     * Walks through all recorded entries and adds the data available
     * from the local file header.
     *
     *
     * Also records the offsets for the data to read from the
     * entries.
     */
    @Throws(IOException::class)
    private fun resolveLocalFileHeaderData(entriesWithoutUTF8Flag: Map<ZipArchiveEntry, NameAndComment>) {
        for (zipArchiveEntry in entries) {
            // entries is filled in populateFromCentralDirectory and
            // never modified
            val ze = zipArchiveEntry as Entry
            val lens = setDataOffset(ze)
            val fileNameLen = lens[0]
            val extraFieldLen = lens[1]
            skipBytes(fileNameLen)
            val localExtraData = ByteArray(extraFieldLen)
            archive.readFully(ByteBuffer.wrap(localExtraData))
            ze.extra = localExtraData
            if (entriesWithoutUTF8Flag.containsKey(ze)) {
                val nc = entriesWithoutUTF8Flag[ze]
                ZipUtil.setNameAndCommentFromExtraFields(
                    ze, nc!!.name,
                    nc.comment
                )
            }
        }
    }

    private fun fillNameMap() {
        for (ze in entries) {
            // entries is filled in populateFromCentralDirectory and
            // never modified
            val name = ze.getName()
            var entriesOfThatName = nameMap[name]
            if (entriesOfThatName == null) {
                entriesOfThatName = LinkedList()
                nameMap[name] = entriesOfThatName
            }
            entriesOfThatName.addLast(ze)
        }
    }

    @Throws(IOException::class)
    private fun setDataOffset(ze: ZipArchiveEntry): IntArray {
        var offset = ze.localHeaderOffset
        if (isSplitZipArchive) {
            (archive as ZipSplitReadOnlyFileChannel)
                .position(ze.diskNumberStart, offset + LFH_OFFSET_FOR_FILENAME_LENGTH)
            // the offset should be updated to the global offset
            offset = archive.position() - LFH_OFFSET_FOR_FILENAME_LENGTH
        } else {
            archive.position(offset + LFH_OFFSET_FOR_FILENAME_LENGTH)
        }
        wordBbuf.rewind()
        archive.readFully(wordBbuf)
        wordBbuf.flip()
        wordBbuf[shortBuf]
        val fileNameLen = ZipShort.getValue(shortBuf)
        wordBbuf[shortBuf]
        val extraFieldLen = ZipShort.getValue(shortBuf)
        ze.dataOffset = (offset + LFH_OFFSET_FOR_FILENAME_LENGTH
                + ZipConstants.SHORT + ZipConstants.SHORT + fileNameLen + extraFieldLen)
        return intArrayOf(fileNameLen, extraFieldLen)
    }

    @Throws(IOException::class)
    private fun getDataOffset(ze: ZipArchiveEntry): Long {
        val s = ze.dataOffset
        if (s == EntryStreamOffsets.OFFSET_UNKNOWN) {
            setDataOffset(ze)
            return ze.dataOffset
        }
        return s
    }

    /**
     * Checks whether the archive starts with a LFH.  If it doesn't,
     * it may be an empty archive.
     */
    @Throws(IOException::class)
    private fun startsWithLocalFileHeader(): Boolean {
        archive.position(0)
        wordBbuf.rewind()
        archive.readFully(wordBbuf)
        return wordBuf.contentEquals(ZipArchiveOutputStream.LFH_SIG)
    }

    /**
     * Creates new BoundedInputStream, according to implementation of
     * underlying archive channel.
     */
    private fun createBoundedInputStream(start: Long, remaining: Long): BoundedInputStream {
        return BoundedInputStream(start, remaining)
        /* Changed to SeekableByteChannel to FileChannel
        return if (archive is FileChannel) BoundedFileChannelInputStream(
            start,
            remaining
        ) else BoundedInputStream(start, remaining)*/
    }

    /**
     * InputStream that delegates requests to the underlying
     * [FileChannel], making sure that only bytes from a certain
     * range can be read.
     */
    private inner class BoundedInputStream(start: Long, remaining: Long) : InputStream() {
        private var singleByteBuffer: ByteBuffer? = null
        private val end = start + remaining
        private var loc: Long

        @Synchronized
        @Throws(IOException::class)
        override fun read(): Int {
            if (loc >= end) {
                return -1
            }
            if (singleByteBuffer == null) {
                singleByteBuffer = ByteBuffer.allocate(1)
            } else {
                singleByteBuffer!!.rewind()
            }
            val read = read(loc, singleByteBuffer)
            if (read < 0) {
                return read
            }
            loc++
            return singleByteBuffer!!.get().toInt() and 0xff
        }

        @Synchronized
        @Throws(IOException::class)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var length = len
            if (length <= 0)
                return 0

            if (length > end - loc) {
                if (loc >= end) {
                    return -1
                }
                length = (end - loc).toInt()
            }
            val buf = ByteBuffer.wrap(b, off, length)
            val ret = read(loc, buf)
            if (ret > 0) {
                loc += ret.toLong()
                return ret
            }
            return ret
        }

        @Throws(IOException::class)
        fun read(pos: Long, buf: ByteBuffer?): Int {
            val read = archive.read(buf, pos)
            buf!!.flip()
            return read
        }

        init {
            require(end >= start) {
                // check for potential vulnerability due to overflow
                "Invalid length of stream at offset=$start, length=$remaining"
            }
            loc = start
        }
    }

    private class NameAndComment(val name: ByteArray, val comment: ByteArray)

    /**
     * Compares two ZipArchiveEntries based on their offset within the archive.
     *
     *
     * Won't return any meaningful results if one of the entries
     * isn't part of the archive at all.
     */
    private val offsetComparator: Comparator<ZipArchiveEntry> = Comparator { e1, e2 ->
        if (e1 === e2) {
            return@Comparator 0
        }
        val ent1 = if (e1 is Entry) e1 else null
        val ent2 = if (e2 is Entry) e2 else null
        if (ent1 == null) {
            return@Comparator 1
        }
        if (ent2 == null) {
            return@Comparator -1
        }

        // disk number is prior to relative offset
        val diskNumberStartVal = ent1.diskNumberStart - ent2.diskNumberStart
        if (diskNumberStartVal != 0L) {
            return@Comparator if (diskNumberStartVal < 0) -1 else +1
        }
        val value = ((ent1.localHeaderOffset
                - ent2.localHeaderOffset))
        if (value == 0L) 0 else if (value < 0) -1 else +1
    }

    /**
     * Extends ZipArchiveEntry to store the offset within the archive.
     */
    private class Entry : ZipArchiveEntry() {
        override fun hashCode(): Int =
            3 * super.hashCode() + localHeaderOffset.toInt() + (localHeaderOffset shr 32).toInt()

        override fun equals(other: Any?): Boolean {
            if (super.equals(other)) {
                // super.equals would return false if other were not an Entry
                val otherEntry = other as Entry
                return (localHeaderOffset == otherEntry.localHeaderOffset)
                        && (super.dataOffset == otherEntry.dataOffset)
                        && (super.diskNumberStart == otherEntry.diskNumberStart)
            }
            return false
        }
    }

    private class StoredStatisticsStream(inputStream: InputStream) :
        CountingInputStream(inputStream),
        InputStreamStatistics {

        override val compressedCount: Long
            get() = super.bytesRead

        override val uncompressedCount: Long
            get() = compressedCount
    }

    public companion object {
        private const val HASH_SIZE = 509
        internal const val NIBLET_MASK = 0x0f
        internal const val BYTE_SHIFT = 8
        private const val POS_0 = 0
        private const val POS_1 = 1
        private const val POS_2 = 2
        private const val POS_3 = 3
        private val ONE_ZERO_BYTE = ByteArray(1)

        /**
         * Length of a "central directory" entry structure without file
         * name, extra fields or comment.
         */
        private const val CFH_LEN =  /* version made by                 */
            (ZipConstants.SHORT /* version needed to extract       */
                    + ZipConstants.SHORT /* general purpose bit flag        */
                    + ZipConstants.SHORT /* compression method              */
                    + ZipConstants.SHORT /* last mod file time              */
                    + ZipConstants.SHORT /* last mod file date              */
                    + ZipConstants.SHORT /* crc-32                          */
                    + ZipConstants.WORD /* compressed size                 */
                    + ZipConstants.WORD /* uncompressed size               */
                    + ZipConstants.WORD /* file name length                 */
                    + ZipConstants.SHORT /* extra field length              */
                    + ZipConstants.SHORT /* file comment length             */
                    + ZipConstants.SHORT /* disk number start               */
                    + ZipConstants.SHORT /* internal file attributes        */
                    + ZipConstants.SHORT /* external file attributes        */
                    + ZipConstants.WORD /* relative offset of local header */
                    + ZipConstants.WORD)
        private val CFH_SIG = ZipLong.getValue(ZipArchiveOutputStream.CFH_SIG)

        /**
         * Length of the "End of central directory record" - which is
         * supposed to be the last structure of the archive - without file
         * comment.
         */
        internal const val MIN_EOCD_SIZE =  /* end of central dir signature    */
            (ZipConstants.WORD /* number of this disk             */
                    + ZipConstants.SHORT /* number of the disk with the     */ /* start of the central directory  */
                    + ZipConstants.SHORT /* total number of entries in      */ /* the central dir on this disk    */
                    + ZipConstants.SHORT /* total number of entries in      */ /* the central dir                 */
                    + ZipConstants.SHORT /* size of the central directory   */
                    + ZipConstants.WORD /* offset of start of central      */ /* directory with respect to       */ /* the starting disk number        */
                    + ZipConstants.WORD /* zipfile comment length          */
                    + ZipConstants.SHORT)

        /**
         * Maximum length of the "End of central directory record" with a
         * file comment.
         */
        private const val MAX_EOCD_SIZE = (MIN_EOCD_SIZE + ZipConstants.ZIP64_MAGIC_SHORT)

        /**
         * Offset of the field that holds the location of the first
         * central directory entry inside the "End of central directory
         * record" relative to the start of the "End of central directory
         * record".
         */
        private const val CFD_LOCATOR_OFFSET =  /* end of central dir signature    */
            (ZipConstants.WORD /* number of this disk             */
                    + ZipConstants.SHORT /* number of the disk with the     */ /* start of the central directory  */
                    + ZipConstants.SHORT /* total number of entries in      */ /* the central dir on this disk    */
                    + ZipConstants.SHORT /* total number of entries in      */ /* the central dir                 */
                    + ZipConstants.SHORT /* size of the central directory   */
                    + ZipConstants.WORD)

        /**
         * Offset of the field that holds the disk number of the first
         * central directory entry inside the "End of central directory
         * record" relative to the start of the "End of central directory
         * record".
         */
        private const val CFD_DISK_OFFSET =
            ( /* end of central dir signature    */ZipConstants.WORD /* number of this disk             */
                    + ZipConstants.SHORT)

        /**
         * Offset of the field that holds the location of the first
         * central directory entry inside the "End of central directory
         * record" relative to the "number of the disk with the start
         * of the central directory".
         */
        private const val CFD_LOCATOR_RELATIVE_OFFSET =  /* total number of entries in      */
            /* the central dir on this disk    */
            (+ZipConstants.SHORT /* total number of entries in      */ /* the central dir                 */
                    + ZipConstants.SHORT /* size of the central directory   */
                    + ZipConstants.WORD)

        /**
         * Length of the "Zip64 end of central directory locator" - which
         * should be right in front of the "end of central directory
         * record" if one is present at all.
         */
        private const val ZIP64_EOCDL_LENGTH =  /* zip64 end of central dir locator sig */
            (ZipConstants.WORD /* number of the disk with the start    */ /* start of the zip64 end of            */ /* central directory                    */
                    + ZipConstants.WORD /* relative offset of the zip64         */ /* end of central directory record      */
                    + ZipConstants.DWORD /* total number of disks                */
                    + ZipConstants.WORD)

        /**
         * Offset of the field that holds the location of the "Zip64 end
         * of central directory record" inside the "Zip64 end of central
         * directory locator" relative to the start of the "Zip64 end of
         * central directory locator".
         */
        private const val ZIP64_EOCDL_LOCATOR_OFFSET =
            ( /* zip64 end of central dir locator sig */ZipConstants.WORD /* number of the disk with the start    */ /* start of the zip64 end of            */ /* central directory                    */
                    + ZipConstants.WORD)

        /**
         * Offset of the field that holds the location of the first
         * central directory entry inside the "Zip64 end of central
         * directory record" relative to the start of the "Zip64 end of
         * central directory record".
         */
        private const val ZIP64_EOCD_CFD_LOCATOR_OFFSET =  /* zip64 end of central dir        */
            /* signature                       */
            (ZipConstants.WORD /* size of zip64 end of central    */ /* directory record                */
                    + ZipConstants.DWORD /* version made by                 */
                    + ZipConstants.SHORT /* version needed to extract       */
                    + ZipConstants.SHORT /* number of this disk             */
                    + ZipConstants.WORD /* number of the disk with the     */ /* start of the central directory  */
                    + ZipConstants.WORD /* total number of entries in the  */ /* central directory on this disk  */
                    + ZipConstants.DWORD /* total number of entries in the  */ /* central directory               */
                    + ZipConstants.DWORD /* size of the central directory   */
                    + ZipConstants.DWORD)

        /**
         * Offset of the field that holds the disk number of the first
         * central directory entry inside the "Zip64 end of central
         * directory record" relative to the start of the "Zip64 end of
         * central directory record".
         */
        private const val ZIP64_EOCD_CFD_DISK_OFFSET =  /* zip64 end of central dir        */
            /* signature                       */
            (ZipConstants.WORD /* size of zip64 end of central    */ /* directory record                */
                    + ZipConstants.DWORD /* version made by                 */
                    + ZipConstants.SHORT /* version needed to extract       */
                    + ZipConstants.SHORT /* number of this disk             */
                    + ZipConstants.WORD)

        /**
         * Offset of the field that holds the location of the first
         * central directory entry inside the "Zip64 end of central
         * directory record" relative to the "number of the disk
         * with the start of the central directory".
         */
        private const val ZIP64_EOCD_CFD_LOCATOR_RELATIVE_OFFSET =
                /* total number of entries in the  */
            /* central directory on this disk  */
            (ZipConstants.DWORD /* total number of entries in the  */ /* central directory               */
                    + ZipConstants.DWORD /* size of the central directory   */
                    + ZipConstants.DWORD)

        /**
         * Number of bytes in local file header up to the &quot;length of
         * file name&quot; entry.
         */
        private const val LFH_OFFSET_FOR_FILENAME_LENGTH =  /* local file header signature     */
            (ZipConstants.WORD /* version needed to extract       */
                    + ZipConstants.SHORT /* general purpose bit flag        */
                    + ZipConstants.SHORT /* compression method              */
                    + ZipConstants.SHORT /* last mod file time              */
                    + ZipConstants.SHORT /* last mod file date              */
                    + ZipConstants.SHORT /* crc-32                          */
                    + ZipConstants.WORD /* compressed size                 */
                    + ZipConstants.WORD /* uncompressed size               */
                    + ZipConstants.WORD.toLong())
    }

    init {
        var success = false
        try {
            val entriesWithoutUTF8Flag = populateFromCentralDirectory()
            if (!ignoreLocalFileHeader) {
                resolveLocalFileHeaderData(entriesWithoutUTF8Flag)
            }
            fillNameMap()
            success = true
        } finally {
            closed = !success
            if (!success && closeOnError) {
                archive.closeQuietly()
            }
        }
    }
}