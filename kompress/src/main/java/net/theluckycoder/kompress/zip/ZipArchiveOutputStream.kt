package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.archivers.ArchiveEntry
import net.theluckycoder.kompress.archivers.ArchiveOutputStream
import net.theluckycoder.kompress.utils.closeQuietly
import net.theluckycoder.kompress.utils.randomAccessFileChannel
import net.theluckycoder.kompress.zip.ZipArchiveOutputStream.Companion.DEFLATED
import net.theluckycoder.kompress.zip.ZipArchiveOutputStream.Companion.STORED
import net.theluckycoder.kompress.zip.ZipConstants.SHORT
import net.theluckycoder.kompress.zip.ZipConstants.WORD
import net.theluckycoder.kompress.zip.ZipConstants.ZIP64_MAGIC
import net.theluckycoder.kompress.zip.ZipLong.Utils.putLong
import net.theluckycoder.kompress.zip.ZipShort.Utils.putShort
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.*
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import kotlin.math.min

/**
 * Reimplementation of [java.util.zip.ZipOutputStream] that does handle the extended
 * functionality of this package, especially internal/external file
 * attributes and extra fields with different layouts for local file
 * data and central directory entries.
 *
 *
 * This class will try to use [FileChannel] when it knows that the
 * output is going to go to a file and no split archive shall be
 * created.
 *
 *
 * If SeekableByteChannel cannot be used, this implementation will use
 * a Data Descriptor to store size and CRC information for [DEFLATED] entries, this means, you don't need to
 * calculate them yourself.  Unfortunately this is not possible for
 * the [STORED] method, here setting the CRC and
 * uncompressed size information is required before [putArchiveEntry] can be called.
 *
 *
 * As of Apache Commons Compress 1.3 it transparently supports Zip64
 * extensions and thus individual entries and archives larger than 4
 * GB or with more than 65536 entries in most cases but explicit
 * control is provided via [setUseZip64].  If the stream can not
 * use SeekableByteChannel and you try to write a ZipArchiveEntry of
 * unknown size then Zip64 extensions will be disabled by default.
 *
 * @NotThreadSafe
 */
@Suppress("unused")
public class ZipArchiveOutputStream : ArchiveOutputStream {

    private var finished = false

    /**
     * Current entry.
     */
    private var entry: CurrentEntry? = null

    /**
     * The file comment.
     */
    public var comment: String = ""

    /**
     * Compression level for next entry.
     */
    private var level = DEFAULT_COMPRESSION

    /**
     * Has the compression level changed when compared to the last
     * entry?
     */
    private var hasCompressionLevelChanged = false

    /**
     * Default compression method for next entry
     *
     * An `int` from [java.util.zip.ZipEntry]
     */
    public var method: Int = ZipEntry.DEFLATED

    /**
     * List of ZipArchiveEntries written so far.
     */
    private val entries: MutableList<ZipArchiveEntry> = LinkedList()
    private val streamCompressor: StreamCompressor

    /**
     * Start of central directory.
     */
    private var cdOffset: Long = 0

    /**
     * Length of central directory.
     */
    private var cdLength: Long = 0

    /**
     * Disk number start of central directory.
     */
    private var cdDiskNumberStart: Long = 0

    /**
     * Length of end of central directory
     */
    private var eocdLength: Long = 0

    /**
     * Holds some book-keeping data for each entry.
     */
    private val metaData: MutableMap<ZipArchiveEntry, EntryMetaData> = HashMap()

    /**
     * The encoding to use for file names and the file comment.
     *
     *
     * For a list of possible values see (http://java.sun.com/j2se/1.5.0/docs/guide/intl/encoding.doc.html).
     * Defaults to UTF-8.
     *
     * Set @null for Platform Default
     */
    public var encoding: String? = DEFAULT_ENCODING
        set(value) {
            field = value
            zipEncoding = ZipEncodingHelper.getZipEncoding(encoding)
            if (useUTF8Flag && !ZipEncodingHelper.isUTF8(encoding))
                useUTF8Flag = false
        }

    /**
     * The zip encoding to use for file names and the file comment.
     *
     * This field is of internal use and will be set in accordance with [encoding].
     */
    private var zipEncoding = ZipEncodingHelper.getZipEncoding(DEFAULT_ENCODING)

    /**
     * This Deflater object is used for output.
     */
    private val def: Deflater

    /**
     * Optional random access output.
     */
    private val channel: FileChannel?
    private val out: OutputStream?

    /**
     * whether to use the general purpose bit flag when writing UTF-8
     * file names or not.
     */
    private var useUTF8Flag = true

    /**
     * Whether to encode non-encodable file names as UTF-8.
     */
    private var fallbackToUTF8 = false

    /**
     * whether to create UnicodePathExtraField-s for each entry.
     */
    private var createUnicodeExtraFields = UnicodeExtraFieldPolicy.NEVER

    /**
     * Whether anything inside this archive has used a ZIP64 feature.
     */
    private var hasUsedZip64 = false
    private var zip64Mode = Zip64Mode.AsNeeded
    private val copyBuffer = ByteArray(32768)
    private val calendarInstance = Calendar.getInstance()

    /**
     * Whether we are creating a split zip
     */
    private val isSplitZip: Boolean

    /**
     * Holds the number of Central Directories on each disk, this is used
     * when writing Zip64 End Of Central Directory and End Of Central Directory
     */
    private val numberOfCDInDiskData: MutableMap<Int, Int?> = HashMap()

    /**
     * Creates a new ZIP OutputStream filtering the underlying stream.
     * @param out the OutputStream to zip
     */
    public constructor(out: OutputStream) {
        this.out = out
        channel = null
        def = Deflater(level, true)
        streamCompressor = StreamCompressor.create(out, def)
        isSplitZip = false
    }

    /**
     * Creates a new ZIP OutputStream writing to a File.  Will use
     * random access if possible.
     * @param file the file to zip to
     * @throws IOException on error
     */
    public constructor(file: File) {
        def = Deflater(level, true)
        var outputStream: OutputStream? = null
        var channel: FileChannel? = null
        var streamCompressor: StreamCompressor

        try {
            if (file.exists())
                file.delete()

            channel = file.randomAccessFileChannel("rw")
            /*channel = Files.newByteChannel(
                file.toPath(),
                EnumSet.of(
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.READ,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            )*/
            // will never get opened properly when an exception is thrown so doesn't need to get closed
            streamCompressor = StreamCompressor.create(channel, def)
        } catch (e: IOException) {
            channel?.closeQuietly()
            channel = null
            outputStream = file.outputStream()
            streamCompressor = StreamCompressor.create(outputStream, def)
        }

        this.out = outputStream
        this.channel = channel
        this.streamCompressor = streamCompressor
        isSplitZip = false
    }

    /**
     * Creates a split ZIP Archive.
     *
     *
     * The files making up the archive will use Z01, Z02,
     * ... extensions and the last part of it will be the given `file`.
     *
     *
     * Even though the stream writes to a file this stream will
     * behave as if no random access was possible. This means the
     * sizes of stored entries need to be known before the actual
     * entry data is written.
     *
     * @param file the file that will become the last part of the split archive
     * @param zipSplitSize maximum size of a single part of the split
     * archive created by this stream. Must be between 64kB and about
     * 4GB.
     *
     * @throws IOException on error
     * @throws IllegalArgumentException if zipSplitSize is not in the required range
     */
    public constructor(file: File, zipSplitSize: Long) {
        def = Deflater(level, true)
        out = ZipSplitOutputStream(file, zipSplitSize)
        streamCompressor = StreamCompressor.create(out, def)
        channel = null
        isSplitZip = true
    }

    /**
     * Creates a new ZIP OutputStream writing to a [FileChannel].
     *
     * @param channel the channel to zip to
     * @throws IOException on error
     */
    public constructor(channel: FileChannel) {
        this.channel = channel
        def = Deflater(level, true)
        streamCompressor = StreamCompressor.create(channel, def)
        out = null
        isSplitZip = false
    }

    /**
     * This method indicates whether this archive is writing to a
     * seekable stream (i.e., to a random access file).
     *
     *
     * For seekable streams, you don't need to calculate the CRC or
     * uncompressed size for [STORED] entries before
     * invoking [putArchiveEntry].
     * @return true if seekable
     */
    public val isSeekable: Boolean
        get() = channel != null

    /**
     * Whether to set the language encoding flag if the file name
     * encoding is UTF-8.
     *
     *
     * Defaults to true.
     *
     * @param bool whether to set the language encoding flag if the file
     * name encoding is UTF-8
     */
    public fun setUseLanguageEncodingFlag(bool: Boolean) {
        useUTF8Flag = bool && ZipEncodingHelper.isUTF8(encoding)
    }

    /**
     * Whether to create Unicode Extra Fields.
     *
     *
     * Defaults to NEVER.
     *
     * @param bool whether to create Unicode Extra Fields.
     */
    public fun setCreateUnicodeExtraFields(bool: UnicodeExtraFieldPolicy) {
        createUnicodeExtraFields = bool
    }

    /**
     * Whether to fall back to UTF and the language encoding flag if
     * the file name cannot be encoded using the specified encoding.
     *
     *
     * Defaults to false.
     *
     * @param b whether to fall back to UTF and the language encoding
     * flag if the file name cannot be encoded using the specified
     * encoding.
     */
    public fun setFallbackToUTF8(b: Boolean) {
        fallbackToUTF8 = b
    }

    /**
     * Whether Zip64 extensions will be used.
     *
     *
     * When setting the mode to [Zip64Mode.Never],
     * [putArchiveEntry], [closeArchiveEntry], [finish] or [close] may throw a [Zip64RequiredException] if the entry's size or the total size
     * of the archive exceeds 4GB or there are more than 65536 entries
     * inside the archive.  Any archive created in this mode will be
     * readable by implementations that don't support Zip64.
     *
     *
     * When setting the mode to [Zip64Mode.Always],
     * Zip64 extensions will be used for all entries.  Any archive
     * created in this mode may be unreadable by implementations that
     * don't support Zip64 even if all its contents would be.
     *
     *
     * When setting the mode to [Zip64Mode.AsNeeded], Zip64 extensions will transparently be used for
     * those entries that require them. This mode can only be used if
     * the uncompressed size of the [ZipArchiveEntry] is known
     * when calling [putArchiveEntry] or the archive is written
     * to a seekable output (i.e. you have used the [ZipArchiveOutputStream]) -
     * this mode is not valid when the output stream is not seekable
     * and the uncompressed size is unknown when [putArchiveEntry] is called.
     *
     *
     * If no entry inside the resulting archive requires Zip64
     * extensions then [Zip64Mode.Never] will create the
     * smallest archive.  [Zip64Mode.AsNeeded] will
     * create a slightly bigger archive if the uncompressed size of
     * any entry has initially been unknown and create an archive
     * identical to [Zip64Mode.Never] otherwise.  [Zip64Mode.Always] will create an archive that is at
     * least 24 bytes per entry bigger than the one [Zip64Mode.Never] would create.
     *
     *
     * Defaults to [Zip64Mode.AsNeeded] unless
     * [putArchiveEntry] is called with an entry of unknown
     * size and data is written to a non-seekable stream - in this
     * case the default is [Zip64Mode.Never].
     *
     * @param mode Whether Zip64 extensions will be used.
     */
    public fun setUseZip64(mode: Zip64Mode) {
        zip64Mode = mode
    }

    /**
     * {@inheritDoc}
     * @throws Zip64RequiredException if the archive's size exceeds 4
     * GByte or there are more than 65535 entries inside the archive
     * and [setUseZip64] is [Zip64Mode.Never].
     */
    @Throws(IOException::class)
    override fun finish() {
        if (finished)
            throw IOException("This archive has already been finished")

        if (entry != null)
            throw IOException("This archive contains unclosed entries.")

        val cdOverallOffset = streamCompressor.totalBytesWritten
        cdOffset = cdOverallOffset
        if (isSplitZip) {
            // when creating a split zip, the offset should be
            // the offset to the corresponding segment disk
            val zipSplitOutputStream = out as ZipSplitOutputStream
            cdOffset = zipSplitOutputStream.currentSplitSegmentBytesWritten
            cdDiskNumberStart = zipSplitOutputStream.currentSplitSegmentIndex.toLong()
        }
        writeCentralDirectoryInChunks()
        cdLength = streamCompressor.totalBytesWritten - cdOverallOffset

        // calculate the length of end of central directory, as it may be used in writeZip64CentralDirectory
        val commentData = zipEncoding.encode(comment)
        val commentLength = commentData.limit().toLong() - commentData.position()
        eocdLength = (WORD /* length of EOCD_SIG */
                + SHORT /* number of this disk */
                + SHORT /* disk number of start of central directory */
                + SHORT /* total number of entries on this disk */
                + SHORT /* total number of entries */
                + WORD /* size of central directory */
                + WORD /* offset of start of central directory */
                + SHORT /* zip comment length */
                + commentLength) /* zip comment */
        writeZip64CentralDirectory()
        writeCentralDirectoryEnd()
        metaData.clear()
        entries.clear()
        streamCompressor.close()
        if (isSplitZip) {
            // trigger the ZipSplitOutputStream to write the final split segment
            out!!.close()
        }
        finished = true
    }

    @Throws(IOException::class)
    private fun writeCentralDirectoryInChunks() {
        val numberPerWrite = 1000

        val byteArrayOutputStream = ByteArrayOutputStream(70 * numberPerWrite)
        var count = 0
        for (ze in entries) {
            byteArrayOutputStream.write(createCentralFileHeader(ze))
            if (++count > numberPerWrite) {
                writeCounted(byteArrayOutputStream.toByteArray())
                byteArrayOutputStream.reset()
                count = 0
            }
        }
        writeCounted(byteArrayOutputStream.toByteArray())
    }

    /**
     * Writes all necessary data for this entry.
     * @throws IOException on error
     * @throws Zip64RequiredException if the entry's uncompressed or
     * compressed size exceeds 4 GByte and [setUseZip64] is [Zip64Mode.Never].
     */
    @Throws(IOException::class)
    override fun closeArchiveEntry() {
        preClose()
        flushDeflater()

        val entry = entry!!
        val bytesWritten = streamCompressor.totalBytesWritten - entry.dataStart
        val realCrc = streamCompressor.crc32
        entry.bytesRead = streamCompressor.bytesRead
        val effectiveMode = getEffectiveZip64Mode(entry.entry)
        val actuallyNeedsZip64 = handleSizesAndCrc(bytesWritten, realCrc, effectiveMode)
        closeEntry(actuallyNeedsZip64, false)
        streamCompressor.reset()
    }

    /**
     * Writes all necessary data for this entry.
     *
     * @param phased              This entry is second phase of a 2-phase zip creation, size, compressed size and crc
     * are known in ZipArchiveEntry
     * @throws IOException            on error
     * @throws Zip64RequiredException if the entry's uncompressed or
     * compressed size exceeds 4 GByte and [setUseZip64]
     * is [Zip64Mode.Never].
     */
    @Throws(IOException::class)
    private fun closeCopiedEntry(phased: Boolean) {
        preClose()
        entry!!.bytesRead = entry!!.entry.size
        val effectiveMode = getEffectiveZip64Mode(entry!!.entry)
        val actuallyNeedsZip64 = checkIfNeedsZip64(effectiveMode)
        closeEntry(actuallyNeedsZip64, phased)
    }

    @Throws(IOException::class)
    private fun closeEntry(actuallyNeedsZip64: Boolean, phased: Boolean) {
        if (!phased && channel != null) {
            rewriteSizesAndCrc(actuallyNeedsZip64)
        }
        if (!phased) {
            writeDataDescriptor(entry!!.entry)
        }
        entry = null
    }

    @Throws(IOException::class)
    private fun preClose() {
        if (finished) {
            throw IOException("Stream has already been finished")
        }
        if (entry == null) {
            throw IOException("No current entry to close")
        }
        if (!entry!!.hasWritten) {
            write(EMPTY, 0, 0)
        }
    }

    /**
     * Adds an archive entry with a raw input stream.
     *
     * If crc, size and compressed size are supplied on the entry, these values will be used as-is.
     * Zip64 status is re-established based on the settings in this stream, and the supplied value
     * is ignored.
     *
     * The entry is put and closed immediately.
     *
     * @param entry The archive entry to add
     * @param rawStream The raw input stream of a different entry. May be compressed/encrypted.
     * @throws IOException If copying fails
     */
    @Throws(IOException::class)
    public fun addRawArchiveEntry(entry: ZipArchiveEntry, rawStream: InputStream) {
        val ae = ZipArchiveEntry(entry)
        if (hasZip64Extra(ae)) {
            // Will be re-added as required. this may make the file generated with this method
            // somewhat smaller than standard mode,
            // since standard mode is unable to remove the zip 64 header.
            ae.removeExtraField(Zip64ExtendedInformationExtraField.HEADER_ID)
        }
        val is2PhaseSource =
            ae.crc != ZipArchiveEntry.CRC_UNKNOWN.toLong() && ae.size != ArchiveEntry.SIZE_UNKNOWN && ae.compressedSize != ArchiveEntry.SIZE_UNKNOWN
        putArchiveEntry(ae, is2PhaseSource)
        copyFromZipInputStream(rawStream)
        closeCopiedEntry(is2PhaseSource)
    }

    /**
     * Ensures all bytes sent to the deflater are written to the stream.
     */
    @Throws(IOException::class)
    private fun flushDeflater() {
        if (entry!!.entry.method == DEFLATED) {
            streamCompressor.flushDeflater()
        }
    }

    /**
     * Ensures the current entry's size and CRC information is set to
     * the values just written, verifies it isn't too big in the
     * Zip64Mode.Never case and returns whether the entry would
     * require a Zip64 extra field.
     */
    @Throws(ZipException::class)
    private fun handleSizesAndCrc(
        bytesWritten: Long, crc: Long,
        effectiveMode: Zip64Mode
    ): Boolean {
        val entry = entry!!
        val e = entry.entry
        if (e.method == DEFLATED) {
            /* It turns out def.getBytesRead() returns wrong values if
             * the size exceeds 4 GB on Java < Java7
            entry.entry.setSize(def.getBytesRead());
            */
            e.size = entry.bytesRead
            e.compressedSize = bytesWritten
            e.crc = crc
        } else if (channel == null) {
            if (e.crc != crc) {
                throw ZipException(
                    "Bad CRC checksum for entry ${e.name}: ${e.crc.toString(16)} instead of ${crc.toString(16)}"
                )
            }
            if (e.size != bytesWritten) {
                throw ZipException(
                    "Bad size for entry ${e.name}: ${e.size} instead of $bytesWritten"
                )
            }
        } else { /* method is STORED and we used SeekableByteChannel */
            e.size = bytesWritten
            e.compressedSize = bytesWritten
            e.crc = crc
        }
        return checkIfNeedsZip64(effectiveMode)
    }

    /**
     * Verifies the sizes aren't too big in the Zip64Mode.Never case
     * and returns whether the entry would require a Zip64 extra
     * field.
     */
    @Throws(ZipException::class)
    private fun checkIfNeedsZip64(effectiveMode: Zip64Mode): Boolean {
        val actuallyNeedsZip64 = isZip64Required(entry!!.entry, effectiveMode)
        if (actuallyNeedsZip64 && effectiveMode == Zip64Mode.Never) {
            throw Zip64RequiredException(
                Zip64RequiredException.getEntryTooBigMessage(entry!!.entry)
            )
        }
        return actuallyNeedsZip64
    }

    private fun isZip64Required(entry1: ZipArchiveEntry, requestedMode: Zip64Mode): Boolean {
        return requestedMode == Zip64Mode.Always || isTooLargeForZip32(entry1)
    }

    private fun isTooLargeForZip32(zipArchiveEntry: ZipArchiveEntry): Boolean {
        return zipArchiveEntry.size >= ZIP64_MAGIC || zipArchiveEntry.compressedSize >= ZIP64_MAGIC
    }

    /**
     * When using random access output, write the local file header
     * and potential the ZIP64 extra containing the correct CRC and
     * compressed/uncompressed sizes.
     */
    @Throws(IOException::class)
    private fun rewriteSizesAndCrc(actuallyNeedsZip64: Boolean) {
        val entry = entry!!
        val save = channel!!.position()
        channel.position(entry.localDataStart)
        writeOut(ZipLong.getBytes(entry.entry.crc))
        if (!hasZip64Extra(entry.entry) || !actuallyNeedsZip64) {
            writeOut(ZipLong.getBytes(entry.entry.compressedSize))
            writeOut(ZipLong.getBytes(entry.entry.size))
        } else {
            writeOut(ZipLong.ZIP64_MAGIC.bytes)
            writeOut(ZipLong.ZIP64_MAGIC.bytes)
        }
        if (hasZip64Extra(entry.entry)) {
            val name = getName(entry.entry)
            val nameLen = name.limit() - name.position()
            // seek to ZIP64 extra, skip header and size information
            channel.position(
                entry.localDataStart + 3 * WORD + 2 * SHORT + nameLen + 2 * SHORT
            )
            // inside the ZIP64 extra uncompressed size comes
            // first, unlike the LFH, CD or data descriptor
            writeOut(ZipEightByteInteger.getBytes(entry.entry.size))
            writeOut(ZipEightByteInteger.getBytes(entry.entry.compressedSize))
            if (!actuallyNeedsZip64) {
                // do some cleanup:
                // * rewrite version needed to extract
                channel.position(entry.localDataStart - 5 * SHORT)
                writeOut(
                    ZipShort.getBytes(
                        versionNeededToExtract(
                            zipMethod = entry.entry.method,
                            zip64 = false,
                            usedDataDescriptor = false
                        )
                    )
                )

                // * remove ZIP64 extra so it doesn't get written
                //   to the central directory
                entry.entry.removeExtraField(Zip64ExtendedInformationExtraField.HEADER_ID)
                entry.entry.setExtra()

                // * reset hasUsedZip64 if it has been set because
                //   of this entry
                if (entry.causedUseOfZip64)
                    hasUsedZip64 = false
            }
        }
        channel.position(save)
    }

    /**
     * {@inheritDoc}
     * @throws ClassCastException if entry is not an instance of ZipArchiveEntry
     * @throws Zip64RequiredException if the entry's uncompressed or
     * compressed size is known to exceed 4 GByte and [setUseZip64]
     * is [Zip64Mode.Never].
     */
    @Throws(IOException::class)
    override fun putArchiveEntry(entry: ArchiveEntry) {
        putArchiveEntry(entry, false)
    }

    /**
     * Writes the headers for an archive entry to the output stream.
     * The caller must then write the content to the stream and call
     * [closeArchiveEntry] to complete the process.
     *
     * @param archiveEntry The archiveEntry
     * @param phased If true size, compressedSize and crc required to be known up-front in the archiveEntry
     * @throws ClassCastException if entry is not an instance of ZipArchiveEntry
     * @throws Zip64RequiredException if the entry's uncompressed or
     * compressed size is known to exceed 4 GByte and [setUseZip64]
     * is [Zip64Mode.Never].
     */
    @Throws(IOException::class)
    private fun putArchiveEntry(archiveEntry: ArchiveEntry, phased: Boolean) {
        if (finished) {
            throw IOException("Stream has already been finished")
        }
        if (entry != null) {
            closeArchiveEntry()
        }
        val entry = CurrentEntry(archiveEntry as ZipArchiveEntry)
        this.entry = entry
        entries.add(entry.entry)
        setDefaults(entry.entry)
        val effectiveMode = getEffectiveZip64Mode(entry.entry)
        validateSizeInformation(effectiveMode)
        if (shouldAddZip64Extra(entry.entry, effectiveMode)) {
            val z64 = getZip64Extra(entry.entry)

            val size: ZipEightByteInteger
            val compressedSize: ZipEightByteInteger
            if (phased) {
                // sizes are already known
                size = ZipEightByteInteger(entry.entry.size)
                compressedSize = ZipEightByteInteger(entry.entry.compressedSize)
            } else if (entry.entry.method == STORED
                && entry.entry.size != ArchiveEntry.SIZE_UNKNOWN
            ) {
                // actually, we already know the sizes
                size = ZipEightByteInteger(entry.entry.size)
                compressedSize = size
            } else {
                // just a placeholder, real data will be in data
                // descriptor or inserted later via SeekableByteChannel
                size = ZipEightByteInteger.ZERO
                compressedSize = size
            }
            z64.size = size
            z64.compressedSize = compressedSize
            entry.entry.setExtra()
        }
        if (entry.entry.method == DEFLATED && hasCompressionLevelChanged) {
            def.setLevel(level)
            hasCompressionLevelChanged = false
        }
        writeLocalFileHeader(archiveEntry, phased)
    }

    /**
     * Provides default values for compression method and last
     * modification time.
     */
    private fun setDefaults(entry: ZipArchiveEntry) {
        if (entry.method == ZipMethod.UNKNOWN_CODE) { // not specified
            entry.method = method
        }
        if (entry.time == -1L) { // not specified
            entry.time = System.currentTimeMillis()
        }
    }

    /**
     * Throws an exception if the size is unknown for a stored entry
     * that is written to a non-seekable output or the entry is too
     * big to be written without Zip64 extra but the mode has been set
     * to Never.
     */
    @Throws(ZipException::class)
    private fun validateSizeInformation(effectiveMode: Zip64Mode) {
        // Size/CRC not required if SeekableByteChannel is used
        if (entry!!.entry.method == STORED && channel == null) {
            if (entry!!.entry.size == ArchiveEntry.SIZE_UNKNOWN) {
                throw ZipException(
                    "Uncompressed size is required for STORED method when not writing to a file"
                )
            }
            if (entry!!.entry.crc == ZipArchiveEntry.CRC_UNKNOWN.toLong()) {
                throw ZipException(
                    "CRC checksum is required for STORED method when not writing to a file"
                )
            }
            entry!!.entry.compressedSize = entry!!.entry.size
        }
        if ((entry!!.entry.size >= ZIP64_MAGIC
                    || entry!!.entry.compressedSize >= ZIP64_MAGIC)
            && effectiveMode == Zip64Mode.Never
        ) {
            throw Zip64RequiredException(
                Zip64RequiredException
                    .getEntryTooBigMessage(entry!!.entry)
            )
        }
    }

    /**
     * Whether to add a Zip64 extended information extra field to the
     * local file header.
     *
     *
     * Returns true if
     *  * mode is Always
     *  * or we already know it is going to be needed
     *  * or the size is unknown and we can ensure it won't hurt
     * other implementations if we add it (i.e. we can erase its
     * usage
     */
    private fun shouldAddZip64Extra(entry: ZipArchiveEntry, mode: Zip64Mode): Boolean {
        return mode == Zip64Mode.Always || entry.size >= ZIP64_MAGIC || entry.compressedSize >= ZIP64_MAGIC
                || entry.size == ArchiveEntry.SIZE_UNKNOWN && channel != null && mode != Zip64Mode.Never
    }

    /**
     * Sets the compression level for subsequent entries.
     *
     *
     * Default is Deflater.DEFAULT_COMPRESSION.
     * @param level the compression level.
     * @throws IllegalArgumentException if an invalid compression
     * level is specified.
     */
    public fun setLevel(level: Int) {
        require(
            !(level < Deflater.DEFAULT_COMPRESSION
                    || level > Deflater.BEST_COMPRESSION)
        ) {
            ("Invalid compression level: "
                    + level)
        }
        if (this.level == level) {
            return
        }
        hasCompressionLevelChanged = true
        this.level = level
    }

    /**
     * Whether this stream is able to write the given entry.
     *
     *
     * May return false if it is set up to use encryption or a
     * compression method that hasn't been implemented yet.
     */
    override fun canWriteEntryData(archiveEntry: ArchiveEntry?): Boolean {
        if (archiveEntry is ZipArchiveEntry) {
            return archiveEntry.method != ZipMethod.IMPLODING.code
                    && archiveEntry.method != ZipMethod.UNSHRINKING.code
                    && ZipUtil.canHandleEntryData(archiveEntry)
        }
        return false
    }

    /**
     * Writes bytes to ZIP entry.
     * @param b the byte array to write
     * @param offset the start position to write from
     * @param length the number of bytes to write
     * @throws IOException on error
     */
    @Throws(IOException::class)
    override fun write(b: ByteArray, offset: Int, length: Int) {
        val entry = entry!!
        ZipUtil.checkRequestedFeatures(entry.entry)
        val writtenThisTime = streamCompressor.write(b, offset, length, entry.entry.method)
        count(writtenThisTime)
    }

    /**
     * Write bytes to output or random access file.
     * @param data the byte array to write
     * @throws IOException on error
     */
    @Throws(IOException::class)
    private fun writeCounted(data: ByteArray) {
        streamCompressor.writeCounted(data)
    }

    @Throws(IOException::class)
    private fun copyFromZipInputStream(src: InputStream) {
        val entry = entry!!
        ZipUtil.checkRequestedFeatures(entry.entry)
        entry.hasWritten = true
        var length: Int
        while (src.read(copyBuffer).also { length = it } >= 0) {
            streamCompressor.writeCounted(copyBuffer, 0, length)
            count(length)
        }
    }

    /**
     * Closes this output stream and releases any system resources
     * associated with the stream.
     *
     * @throws  IOException  if an I/O error occurs.
     * @throws Zip64RequiredException if the archive's size exceeds 4
     * GByte or there are more than 65535 entries inside the archive
     * and [setUseZip64] is [Zip64Mode.Never].
     */
    @Throws(IOException::class)
    override fun close() {
        try {
            if (!finished) {
                finish()
            }
        } finally {
            destroy()
        }
    }

    /**
     * Flushes this output stream and forces any buffered output bytes
     * to be written out to the stream.
     *
     * @throws  IOException  if an I/O error occurs.
     */
    @Throws(IOException::class)
    override fun flush() {
        out?.flush()
    }

    @Throws(IOException::class)
    private fun writeLocalFileHeader(ze: ZipArchiveEntry, phased: Boolean) {
        val encodable = zipEncoding.canEncode(ze.name)
        val name = getName(ze)
        if (createUnicodeExtraFields != UnicodeExtraFieldPolicy.NEVER) {
            addUnicodeExtraFields(ze, encodable, name)
        }
        var localHeaderStart = streamCompressor.totalBytesWritten
        if (isSplitZip) {
            // when creating a split zip, the offset should be
            // the offset to the corresponding segment disk
            val splitOutputStream = out as ZipSplitOutputStream?
            ze.diskNumberStart = splitOutputStream!!.currentSplitSegmentIndex.toLong()
            localHeaderStart = splitOutputStream.currentSplitSegmentBytesWritten
        }
        val localHeader = createLocalFileHeader(ze, name, encodable, phased, localHeaderStart)
        metaData[ze] = EntryMetaData(
            localHeaderStart,
            usesDataDescriptor(ze.method, phased)
        )
        entry!!.localDataStart = localHeaderStart + LFH_CRC_OFFSET // At crc offset
        writeCounted(localHeader)
        entry!!.dataStart = streamCompressor.totalBytesWritten
    }

    private fun createLocalFileHeader(
        ze: ZipArchiveEntry, name: ByteBuffer, encodable: Boolean,
        phased: Boolean, archiveOffset: Long
    ): ByteArray {
        val oldAlignmentEx =
            ze.getExtraField(ResourceAlignmentExtraField.ID) as? ResourceAlignmentExtraField
        if (oldAlignmentEx != null) {
            ze.removeExtraField(ResourceAlignmentExtraField.ID)
        }
        var alignment = ze.alignment
        if (alignment <= 0 && oldAlignmentEx != null) {
            alignment = oldAlignmentEx.alignment.toInt()
        }
        if (alignment > 1 || oldAlignmentEx != null && !oldAlignmentEx.allowMethodChange) {
            val oldLength: Int = LFH_FILENAME_OFFSET +
                    name.limit() - name.position() +
                    ze.localFileDataExtra.size
            val padding = ((-archiveOffset - oldLength - ZipExtraField.EXTRAFIELD_HEADER_SIZE
                    - ResourceAlignmentExtraField.BASE_SIZE) and
                    (alignment - 1).toLong()).toInt()
            ze.addExtraField(
                ResourceAlignmentExtraField(
                    alignment,
                    oldAlignmentEx != null && oldAlignmentEx.allowMethodChange, padding
                )
            )
        }
        val extra = ze.localFileDataExtra
        val nameLen = name.limit() - name.position()
        val len: Int =
            LFH_FILENAME_OFFSET + nameLen + extra.size
        val buf = ByteArray(len)
        System.arraycopy(
            LFH_SIG,
            0,
            buf,
            LFH_SIG_OFFSET,
            WORD
        )

        //store method in local variable to prevent multiple method calls
        val zipMethod = ze.method
        val dataDescriptor = usesDataDescriptor(zipMethod, phased)
        putShort(
            versionNeededToExtract(zipMethod, hasZip64Extra(ze), dataDescriptor),
            buf,
            LFH_VERSION_NEEDED_OFFSET
        )
        val generalPurposeBit = getGeneralPurposeBits(!encodable && fallbackToUTF8, dataDescriptor)
        generalPurposeBit.encode(buf, LFH_GPB_OFFSET)

        // compression method
        putShort(zipMethod, buf, LFH_METHOD_OFFSET)
        ZipUtil.toDosTime(
            calendarInstance,
            ze.time,
            buf,
            LFH_TIME_OFFSET
        )

        // CRC
        if (phased) {
            putLong(ze.crc, buf, LFH_CRC_OFFSET)
        } else if (zipMethod == DEFLATED || channel != null) {
            System.arraycopy(
                LZERO,
                0,
                buf,
                LFH_CRC_OFFSET,
                WORD
            )
        } else {
            putLong(ze.crc, buf, LFH_CRC_OFFSET)
        }

        // compressed length
        // uncompressed length
        if (hasZip64Extra(entry!!.entry)) {
            // point to ZIP64 extended information extra field for
            // sizes, may get rewritten once sizes are known if
            // stream is seekable
            ZipLong.ZIP64_MAGIC.putLong(
                buf,
                LFH_COMPRESSED_SIZE_OFFSET
            )
            ZipLong.ZIP64_MAGIC.putLong(
                buf,
                LFH_ORIGINAL_SIZE_OFFSET
            )
        } else if (phased) {
            putLong(
                ze.compressedSize,
                buf,
                LFH_COMPRESSED_SIZE_OFFSET
            )
            putLong(
                ze.size,
                buf,
                LFH_ORIGINAL_SIZE_OFFSET
            )
        } else if (zipMethod == DEFLATED || channel != null) {
            System.arraycopy(
                LZERO, 0, buf, LFH_COMPRESSED_SIZE_OFFSET, WORD
            )
            System.arraycopy(
                LZERO,
                0,
                buf,
                LFH_ORIGINAL_SIZE_OFFSET,
                WORD
            )
        } else { // Stored
            putLong(ze.size, buf, LFH_COMPRESSED_SIZE_OFFSET)
            putLong(ze.size, buf, LFH_ORIGINAL_SIZE_OFFSET)
        }
        // file name length
        putShort(nameLen, buf, LFH_FILENAME_LENGTH_OFFSET)

        // extra field length
        putShort(extra.size, buf, LFH_EXTRA_LENGTH_OFFSET)

        // file name
        System.arraycopy(name.array(), name.arrayOffset(), buf, LFH_FILENAME_OFFSET, nameLen)

        // extra fields
        System.arraycopy(extra, 0, buf, LFH_FILENAME_OFFSET + nameLen, extra.size)
        return buf
    }

    /**
     * Adds UnicodeExtra fields for name and file comment if mode is
     * ALWAYS or the data cannot be encoded using the configured
     * encoding.
     */
    @Throws(IOException::class)
    private fun addUnicodeExtraFields(zipArchiveEntry: ZipArchiveEntry, encodable: Boolean, name: ByteBuffer) {
        if (createUnicodeExtraFields == UnicodeExtraFieldPolicy.ALWAYS || !encodable)
            zipArchiveEntry.addExtraField(
                UnicodePathExtraField(
                    zipArchiveEntry.name,
                    name.array(),
                    name.arrayOffset(), name.limit()
                            - name.position()
                )
            )

        val entryComment = zipArchiveEntry.comment
        if (entryComment.isNotEmpty()) {
            val commentEncodable = zipEncoding.canEncode(entryComment)

            if (createUnicodeExtraFields == UnicodeExtraFieldPolicy.ALWAYS || !commentEncodable) {
                val commentB = getEntryEncoding(zipArchiveEntry).encode(entryComment)
                zipArchiveEntry.addExtraField(
                    UnicodeCommentExtraField(
                        entryComment,
                        commentB.array(),
                        commentB.arrayOffset(), commentB.limit() - commentB.position()
                    )
                )
            }
        }
    }

    /**
     * Writes the data descriptor entry.
     * @param ze the entry to write
     * @throws IOException on error
     */
    @Throws(IOException::class)
    private fun writeDataDescriptor(ze: ZipArchiveEntry) {
        if (!usesDataDescriptor(ze.method, false)) {
            return
        }
        writeCounted(DD_SIG)
        writeCounted(ZipLong.getBytes(ze.crc))
        if (!hasZip64Extra(ze)) {
            writeCounted(ZipLong.getBytes(ze.compressedSize))
            writeCounted(ZipLong.getBytes(ze.size))
        } else {
            writeCounted(ZipEightByteInteger.getBytes(ze.compressedSize))
            writeCounted(ZipEightByteInteger.getBytes(ze.size))
        }
    }

    @Throws(IOException::class)
    private fun createCentralFileHeader(ze: ZipArchiveEntry): ByteArray {
        val entryMetaData = metaData[ze]
        val needsZip64Extra = (hasZip64Extra(ze)
                || ze.compressedSize >= ZIP64_MAGIC || ze.size >= ZIP64_MAGIC || entryMetaData!!.offset >= ZIP64_MAGIC || ze.diskNumberStart >= ZipConstants.ZIP64_MAGIC_SHORT || zip64Mode == Zip64Mode.Always)
        if (needsZip64Extra && zip64Mode == Zip64Mode.Never) {
            // must be the offset that is too big, otherwise an
            // exception would have been throw in putArchiveEntry or
            // closeArchiveEntry
            throw Zip64RequiredException(Zip64RequiredException.ARCHIVE_TOO_BIG_MESSAGE)
        }
        handleZip64Extra(ze, entryMetaData!!.offset, needsZip64Extra)
        return createCentralFileHeader(ze, getName(ze), entryMetaData, needsZip64Extra)
    }

    /**
     * Writes the central file header entry.
     * @param ze the entry to write
     * @param name The encoded name
     * @param entryMetaData meta data for this file
     * @throws IOException on error
     */
    @Throws(IOException::class)
    private fun createCentralFileHeader(
        ze: ZipArchiveEntry, name: ByteBuffer,
        entryMetaData: EntryMetaData?,
        needsZip64Extra: Boolean
    ): ByteArray {
        if (isSplitZip) {
            // calculate the disk number for every central file header,
            // this will be used in writing End Of Central Directory and Zip64 End Of Central Directory
            val currentSplitSegment = (out as ZipSplitOutputStream?)!!.currentSplitSegmentIndex
            if (numberOfCDInDiskData[currentSplitSegment] == null) {
                numberOfCDInDiskData[currentSplitSegment] = 1
            } else {
                val originalNumberOfCD = numberOfCDInDiskData[currentSplitSegment]!!
                numberOfCDInDiskData[currentSplitSegment] = originalNumberOfCD + 1
            }
        }
        val extra = ze.centralDirectoryExtra

        // file comment length
        var comm = ze.comment
        if (comm == null) {
            comm = ""
        }
        val commentB = getEntryEncoding(ze).encode(comm)
        val nameLen = name.limit() - name.position()
        val commentLen = commentB.limit() - commentB.position()
        val len = CFH_FILENAME_OFFSET + nameLen + extra.size + commentLen
        val buf = ByteArray(len)
        System.arraycopy(CFH_SIG, 0, buf, CFH_SIG_OFFSET, WORD)

        // version made by
        // CheckStyle:MagicNumber OFF
        putShort(
            ze.platform shl 8 or if (!hasUsedZip64) ZipConstants.DATA_DESCRIPTOR_MIN_VERSION else ZipConstants.ZIP64_MIN_VERSION,
            buf, CFH_VERSION_MADE_BY_OFFSET
        )
        val zipMethod = ze.method
        val encodable = zipEncoding.canEncode(ze.name)
        putShort(
            versionNeededToExtract(zipMethod, needsZip64Extra, entryMetaData!!.usesDataDescriptor),
            buf, CFH_VERSION_NEEDED_OFFSET
        )
        getGeneralPurposeBits(!encodable && fallbackToUTF8, entryMetaData.usesDataDescriptor).encode(
            buf,
            CFH_GPB_OFFSET
        )

        // compression method
        putShort(zipMethod, buf, CFH_METHOD_OFFSET)

        // last mod. time and date
        ZipUtil.toDosTime(calendarInstance, ze.time, buf, CFH_TIME_OFFSET)

        // CRC
        // compressed length
        // uncompressed length
        putLong(ze.crc, buf, CFH_CRC_OFFSET)
        if (ze.compressedSize >= ZIP64_MAGIC || ze.size >= ZIP64_MAGIC || zip64Mode == Zip64Mode.Always) {
            ZipLong.ZIP64_MAGIC.putLong(buf, CFH_COMPRESSED_SIZE_OFFSET)
            ZipLong.ZIP64_MAGIC.putLong(buf, CFH_ORIGINAL_SIZE_OFFSET)
        } else {
            putLong(ze.compressedSize, buf, CFH_COMPRESSED_SIZE_OFFSET)
            putLong(ze.size, buf, CFH_ORIGINAL_SIZE_OFFSET)
        }
        putShort(nameLen, buf, CFH_FILENAME_LENGTH_OFFSET)

        // extra field length
        putShort(extra.size, buf, CFH_EXTRA_LENGTH_OFFSET)
        putShort(commentLen, buf, CFH_COMMENT_LENGTH_OFFSET)

        // disk number start
        if (isSplitZip) {
            if (ze.diskNumberStart >= ZipConstants.ZIP64_MAGIC_SHORT || zip64Mode == Zip64Mode.Always) {
                putShort(ZipConstants.ZIP64_MAGIC_SHORT, buf, CFH_DISK_NUMBER_OFFSET)
            } else {
                putShort(ze.diskNumberStart.toInt(), buf, CFH_DISK_NUMBER_OFFSET)
            }
        } else {
            System.arraycopy(ZERO, 0, buf, CFH_DISK_NUMBER_OFFSET, SHORT)
        }

        // internal file attributes
        putShort(ze.internalAttributes, buf, CFH_INTERNAL_ATTRIBUTES_OFFSET)

        // external file attributes
        putLong(ze.externalAttributes, buf, CFH_EXTERNAL_ATTRIBUTES_OFFSET)

        // relative offset of LFH
        if (entryMetaData.offset >= ZIP64_MAGIC || zip64Mode == Zip64Mode.Always) {
            putLong(ZIP64_MAGIC, buf, CFH_LFH_OFFSET)
        } else {
            putLong(min(entryMetaData.offset, ZIP64_MAGIC), buf, CFH_LFH_OFFSET)
        }

        // file name
        System.arraycopy(name.array(), name.arrayOffset(), buf, CFH_FILENAME_OFFSET, nameLen)
        val extraStart = CFH_FILENAME_OFFSET + nameLen
        System.arraycopy(extra, 0, buf, extraStart, extra.size)
        val commentStart = extraStart + extra.size

        // file comment
        System.arraycopy(commentB.array(), commentB.arrayOffset(), buf, commentStart, commentLen)
        return buf
    }

    /**
     * If the entry needs Zip64 extra information inside the central
     * directory then configure its data.
     */
    private fun handleZip64Extra(
        ze: ZipArchiveEntry, lfhOffset: Long,
        needsZip64Extra: Boolean
    ) {
        if (needsZip64Extra) {
            val z64 = getZip64Extra(ze)
            if (ze.compressedSize >= ZIP64_MAGIC || ze.size >= ZIP64_MAGIC || zip64Mode == Zip64Mode.Always) {
                z64.compressedSize = ZipEightByteInteger(ze.compressedSize)
                z64.size = ZipEightByteInteger(ze.size)
            } else {
                // reset value that may have been set for LFH
                z64.compressedSize = null
                z64.size = null
            }
            if (lfhOffset >= ZIP64_MAGIC || zip64Mode == Zip64Mode.Always) {
                z64.relativeHeaderOffset = ZipEightByteInteger(lfhOffset)
            }
            if (ze.diskNumberStart >= ZipConstants.ZIP64_MAGIC_SHORT || zip64Mode == Zip64Mode.Always) {
                z64.diskStartNumber = ZipLong(ze.diskNumberStart)
            }
            ze.setExtra()
        }
    }

    /**
     * Writes the &quot;End of central dir record&quot;.
     * @throws IOException on error
     * @throws Zip64RequiredException if the archive's size exceeds 4
     * GByte or there are more than 65535 entries inside the archive
     * and [#setUseZip64][Zip64Mode] is [Zip64Mode.Never].
     */
    @Throws(IOException::class)
    private fun writeCentralDirectoryEnd() {
        if (!hasUsedZip64 && isSplitZip) {
            (out as ZipSplitOutputStream?)!!.prepareToWriteUnsplittableContent(eocdLength)
        }
        validateIfZip64IsNeededInEOCD()
        writeCounted(EOCD_SIG)

        // number of this disk
        var numberOfThisDisk = 0
        if (isSplitZip) {
            numberOfThisDisk = (out as ZipSplitOutputStream?)!!.currentSplitSegmentIndex
        }
        writeCounted(ZipShort.getBytes(numberOfThisDisk))

        // disk number of the start of central directory
        writeCounted(ZipShort.getBytes(cdDiskNumberStart.toInt()))

        // number of entries
        val numberOfEntries = entries.size

        // total number of entries in the central directory on this disk
        val numOfEntriesOnThisDisk =
            if (isSplitZip) (if (numberOfCDInDiskData[numberOfThisDisk] == null) 0 else numberOfCDInDiskData[numberOfThisDisk])!! else numberOfEntries
        val numOfEntriesOnThisDiskData = ZipShort
            .getBytes(min(numOfEntriesOnThisDisk, ZipConstants.ZIP64_MAGIC_SHORT))
        writeCounted(numOfEntriesOnThisDiskData)

        // number of entries
        val num = ZipShort.getBytes(min(numberOfEntries, ZipConstants.ZIP64_MAGIC_SHORT))
        writeCounted(num)

        // length and location of CD
        writeCounted(ZipLong.getBytes(min(cdLength, ZIP64_MAGIC)))
        writeCounted(ZipLong.getBytes(min(cdOffset, ZIP64_MAGIC)))

        // ZIP file comment
        val data = zipEncoding.encode(comment)
        val dataLen = data.limit() - data.position()
        writeCounted(ZipShort.getBytes(dataLen))
        streamCompressor.writeCounted(data.array(), data.arrayOffset(), dataLen)
    }

    /**
     * If the Zip64 mode is set to never, then all the data in End Of Central Directory
     * should not exceed their limits.
     * @throws Zip64RequiredException if Zip64 is actually needed
     */
    @Throws(Zip64RequiredException::class)
    private fun validateIfZip64IsNeededInEOCD() {
        // exception will only be thrown if the Zip64 mode is never while Zip64 is actually needed
        if (zip64Mode != Zip64Mode.Never) {
            return
        }
        var numberOfThisDisk = 0
        if (isSplitZip) {
            numberOfThisDisk = (out as ZipSplitOutputStream?)!!.currentSplitSegmentIndex
        }
        if (numberOfThisDisk >= ZipConstants.ZIP64_MAGIC_SHORT) {
            throw Zip64RequiredException(Zip64RequiredException.NUMBER_OF_THIS_DISK_TOO_BIG_MESSAGE)
        }
        if (cdDiskNumberStart >= ZipConstants.ZIP64_MAGIC_SHORT) {
            throw Zip64RequiredException(Zip64RequiredException.NUMBER_OF_THE_DISK_OF_CENTRAL_DIRECTORY_TOO_BIG_MESSAGE)
        }
        val numOfEntriesOnThisDisk =
            if (numberOfCDInDiskData[numberOfThisDisk] == null) 0 else numberOfCDInDiskData[numberOfThisDisk]!!
        if (numOfEntriesOnThisDisk >= ZipConstants.ZIP64_MAGIC_SHORT) {
            throw Zip64RequiredException(Zip64RequiredException.TOO_MANY_ENTRIES_ON_THIS_DISK_MESSAGE)
        }

        // number of entries
        if (entries.size >= ZipConstants.ZIP64_MAGIC_SHORT) {
            throw Zip64RequiredException(Zip64RequiredException.TOO_MANY_ENTRIES_MESSAGE)
        }
        if (cdLength >= ZIP64_MAGIC) {
            throw Zip64RequiredException(Zip64RequiredException.SIZE_OF_CENTRAL_DIRECTORY_TOO_BIG_MESSAGE)
        }
        if (cdOffset >= ZIP64_MAGIC) {
            throw Zip64RequiredException(Zip64RequiredException.ARCHIVE_TOO_BIG_MESSAGE)
        }
    }

    /**
     * Writes the &quot;ZIP64 End of central dir record&quot; and
     * &quot;ZIP64 End of central dir locator&quot;.
     * @throws IOException on error
     */
    @Throws(IOException::class)
    private fun writeZip64CentralDirectory() {
        if (zip64Mode == Zip64Mode.Never)
            return
        if (!hasUsedZip64 && shouldUseZip64EOCD()) {
            // actually "will use"
            hasUsedZip64 = true
        }
        if (!hasUsedZip64) {
            return
        }
        var offset = streamCompressor.totalBytesWritten
        var diskNumberStart = 0L
        if (isSplitZip) {
            // when creating a split zip, the offset of should be
            // the offset to the corresponding segment disk
            val zipSplitOutputStream = out as ZipSplitOutputStream?
            offset = zipSplitOutputStream!!.currentSplitSegmentBytesWritten
            diskNumberStart = zipSplitOutputStream.currentSplitSegmentIndex.toLong()
        }
        writeOut(ZIP64_EOCD_SIG)
        // size of zip64 end of central directory, we don't have any variable length
        // as we don't support the extensible data sector, yet
        writeOut(
            ZipEightByteInteger
                .getBytes(
                    SHORT /* version made by */
                            + SHORT /* version needed to extract */
                            + WORD /* disk number */
                            + WORD /* disk with central directory */
                            + ZipConstants.DWORD /* number of entries in CD on this disk */
                            + ZipConstants.DWORD /* total number of entries */
                            + ZipConstants.DWORD /* size of CD */
                            + ZipConstants.DWORD.toLong() /* offset of CD */
                )
        )

        // version made by and version needed to extract
        writeOut(ZipShort.getBytes(ZipConstants.ZIP64_MIN_VERSION))
        writeOut(ZipShort.getBytes(ZipConstants.ZIP64_MIN_VERSION))

        // number of this disk
        var numberOfThisDisk = 0
        if (isSplitZip) {
            numberOfThisDisk = (out as ZipSplitOutputStream?)!!.currentSplitSegmentIndex
        }
        writeOut(ZipLong.getBytes(numberOfThisDisk.toLong()))

        // disk number of the start of central directory
        writeOut(ZipLong.getBytes(cdDiskNumberStart))

        // total number of entries in the central directory on this disk
        val numOfEntriesOnThisDisk =
            if (isSplitZip) (if (numberOfCDInDiskData[numberOfThisDisk] == null) 0 else numberOfCDInDiskData[numberOfThisDisk])!! else entries.size
        val numOfEntriesOnThisDiskData = ZipEightByteInteger.getBytes(numOfEntriesOnThisDisk.toLong())
        writeOut(numOfEntriesOnThisDiskData)

        // number of entries
        val num = ZipEightByteInteger.getBytes(entries.size.toLong())
        writeOut(num)

        // length and location of CD
        writeOut(ZipEightByteInteger.getBytes(cdLength))
        writeOut(ZipEightByteInteger.getBytes(cdOffset))

        // no "zip64 extensible data sector" for now
        if (isSplitZip) {
            // based on the zip specification, the End Of Central Directory record and
            // the Zip64 End Of Central Directory locator record must be on the same segment
            val zip64EOCDLOCLength = (WORD /* length of ZIP64_EOCD_LOC_SIG */
                    + WORD /* disk number of ZIP64_EOCD_SIG */
                    + ZipConstants.DWORD /* offset of ZIP64_EOCD_SIG */
                    + WORD) /* total number of disks */
            val unsplittableContentSize = zip64EOCDLOCLength + eocdLength
            (out as ZipSplitOutputStream?)!!.prepareToWriteUnsplittableContent(unsplittableContentSize)
        }

        // and now the "ZIP64 end of central directory locator"
        writeOut(ZIP64_EOCD_LOC_SIG)

        // disk number holding the ZIP64 EOCD record
        writeOut(ZipLong.getBytes(diskNumberStart))
        // relative offset of ZIP64 EOCD record
        writeOut(ZipEightByteInteger.getBytes(offset))
        // total number of disks
        if (isSplitZip) {
            // the Zip64 End Of Central Directory Locator and the End Of Central Directory must be
            // in the same split disk, it means they must be located in the last disk
            val totalNumberOfDisks = (out as ZipSplitOutputStream?)!!.currentSplitSegmentIndex + 1
            writeOut(ZipLong.getBytes(totalNumberOfDisks.toLong()))
        } else {
            writeOut(ONE)
        }
    }

    /**
     * 4.4.1.4  If one of the fields in the end of central directory
     * record is too small to hold required data, the field SHOULD be
     * set to -1 (0xFFFF or 0xFFFFFFFF) and the ZIP64 format record
     * SHOULD be created.
     * @return true if zip64 End Of Central Directory is needed
     */
    private fun shouldUseZip64EOCD(): Boolean {
        var numberOfThisDisk = 0
        if (isSplitZip) {
            numberOfThisDisk = (out as ZipSplitOutputStream?)!!.currentSplitSegmentIndex
        }
        val numOfEntriesOnThisDisk =
            if (numberOfCDInDiskData[numberOfThisDisk] == null) 0 else numberOfCDInDiskData[numberOfThisDisk]!!
        return numberOfThisDisk >= ZipConstants.ZIP64_MAGIC_SHORT /* number of this disk */ || cdDiskNumberStart >= ZipConstants.ZIP64_MAGIC_SHORT /* number of the disk with the start of the central directory */ || numOfEntriesOnThisDisk >= ZipConstants.ZIP64_MAGIC_SHORT /* total number of entries in the central directory on this disk */ || entries.size >= ZipConstants.ZIP64_MAGIC_SHORT /* total number of entries in the central directory */ || cdLength >= ZIP64_MAGIC /* size of the central directory */ || cdOffset >= ZIP64_MAGIC /* offset of start of central directory with respect to
                                                                the starting disk number */
    }

    /**
     * Write bytes to output or random access file.
     * @param data the byte array to write
     * @throws IOException on error
     */
    @Throws(IOException::class)
    private fun writeOut(data: ByteArray) {
        streamCompressor.writeOut(data, 0, data.size)
    }

    private fun getGeneralPurposeBits(utfFallback: Boolean, usesDataDescriptor: Boolean): GeneralPurposeBit {
        val b = GeneralPurposeBit()
        b.useUTF8ForNames(useUTF8Flag || utfFallback)
        if (usesDataDescriptor) {
            b.useDataDescriptor(true)
        }
        return b
    }

    private fun versionNeededToExtract(zipMethod: Int, zip64: Boolean, usedDataDescriptor: Boolean): Int {
        if (zip64) {
            return ZipConstants.ZIP64_MIN_VERSION
        }
        return if (usedDataDescriptor) {
            ZipConstants.DATA_DESCRIPTOR_MIN_VERSION
        } else versionNeededToExtractMethod(zipMethod)
    }

    private fun usesDataDescriptor(zipMethod: Int, phased: Boolean): Boolean {
        return !phased && zipMethod == DEFLATED && channel == null
    }

    private fun versionNeededToExtractMethod(zipMethod: Int): Int {
        return if (zipMethod == DEFLATED) ZipConstants.DEFLATE_MIN_VERSION else ZipConstants.INITIAL_VERSION
    }

    /**
     * Creates a new zip entry taking some information from the given
     * file and using the provided name.
     *
     *
     * The name will be adjusted to end with a forward slash "/" if
     * the file is a directory.  If the file is not a directory a
     * potential trailing forward slash will be stripped from the
     * entry name.
     *
     *
     * Must not be used if the stream has already been closed.
     */
    @Throws(IOException::class)
    override fun createArchiveEntry(inputFile: File, entryName: String): ArchiveEntry {
        if (finished) {
            throw IOException("Stream has already been finished")
        }
        return ZipArchiveEntry(inputFile, entryName)
    }

    /**
     * Get the existing ZIP64 extended information extra field or
     * create a new one and add it to the entry.
     */
    private fun getZip64Extra(ze: ZipArchiveEntry): Zip64ExtendedInformationExtraField {
        entry?.causedUseOfZip64 = !hasUsedZip64
        hasUsedZip64 = true
        var z64 =
            ze.getExtraField(Zip64ExtendedInformationExtraField.HEADER_ID) as? Zip64ExtendedInformationExtraField
        if (z64 == null) {
            /*
              System.err.println("Adding z64 for " + ze.getName()
              + ", method: " + ze.getMethod()
              + " (" + (ze.getMethod() == STORED) + ")"
              + ", channel: " + (channel != null));
            */
            z64 = Zip64ExtendedInformationExtraField()
        }

        // even if the field is there already, make sure it is the first one
        ze.addAsFirstExtraField(z64)
        return z64
    }

    /**
     * Is there a ZIP64 extended information extra field for the
     * entry?
     */
    private fun hasZip64Extra(ze: ZipArchiveEntry): Boolean {
        return ze.getExtraField(Zip64ExtendedInformationExtraField.HEADER_ID) != null
    }

    /**
     * If the mode is AsNeeded and the entry is a compressed entry of
     * unknown size that gets written to a non-seekable stream then
     * change the default to Never.
     */
    private fun getEffectiveZip64Mode(ze: ZipArchiveEntry): Zip64Mode {
        return if (zip64Mode != Zip64Mode.AsNeeded || channel != null || ze.method != DEFLATED || ze.size != ArchiveEntry.SIZE_UNKNOWN) {
            zip64Mode
        } else Zip64Mode.Never
    }

    private fun getEntryEncoding(ze: ZipArchiveEntry): ZipEncoding {
        val encodable = zipEncoding.canEncode(ze.name)
        return if (!encodable && fallbackToUTF8) ZipEncodingHelper.UTF8_ZIP_ENCODING else zipEncoding
    }

    @Throws(IOException::class)
    private fun getName(ze: ZipArchiveEntry): ByteBuffer {
        return getEntryEncoding(ze).encode(ze.name)
    }

    /**
     * Closes the underlying stream/file without finishing the
     * archive, the result will likely be a corrupt archive.
     *
     *
     * This method only exists to support tests that generate
     * corrupt archives so they can clean up any temporary files.
     */
    @Throws(IOException::class)
    public fun destroy() {
        try {
            channel?.close()
        } finally {
            out?.close()
        }
    }

    /**
     * enum that represents the possible policies for creating Unicode
     * extra fields.
     */
    public class UnicodeExtraFieldPolicy private constructor(private val name: String) {

        override fun toString(): String = name

        public companion object {
            /**
             * Always create Unicode extra fields.
             */
            public val ALWAYS: UnicodeExtraFieldPolicy = UnicodeExtraFieldPolicy("always")

            /**
             * Never create Unicode extra fields.
             */
            public val NEVER: UnicodeExtraFieldPolicy = UnicodeExtraFieldPolicy("never")

            /**
             * Create Unicode extra fields for file names that cannot be
             * encoded using the specified encoding.
             */
            public val NOT_ENCODEABLE: UnicodeExtraFieldPolicy = UnicodeExtraFieldPolicy("not encodeable")
        }
    }

    /**
     * Structure collecting information for the entry that is
     * currently being written.
     */
    private class CurrentEntry(
        /**
         * Current ZIP entry.
         */
        val entry: ZipArchiveEntry
    ) {
        /**
         * Offset for CRC entry in the local file header data for the
         * current entry starts here.
         */
        var localDataStart = 0L

        /**
         * Data for local header data
         */
        var dataStart = 0L

        /**
         * Number of bytes read for the current entry (can't rely on
         * Deflater#getBytesRead) when using DEFLATED.
         */
        var bytesRead = 0L

        /**
         * Whether current entry was the first one using ZIP64 features.
         */
        var causedUseOfZip64 = false

        /**
         * Whether write() has been called at all.
         *
         *
         * In order to create a valid archive [closeArchiveEntry] will write an empty
         * array to get the CRC right if nothing has been written to
         * the stream at all.
         */
        var hasWritten = false
    }

    private class EntryMetaData(val offset: Long, val usesDataDescriptor: Boolean)

    public companion object {
        internal const val BUFFER_SIZE = 512
        private const val LFH_SIG_OFFSET = 0
        private const val LFH_VERSION_NEEDED_OFFSET = 4
        private const val LFH_GPB_OFFSET = 6
        private const val LFH_METHOD_OFFSET = 8
        private const val LFH_TIME_OFFSET = 10
        private const val LFH_CRC_OFFSET = 14
        private const val LFH_COMPRESSED_SIZE_OFFSET = 18
        private const val LFH_ORIGINAL_SIZE_OFFSET = 22
        private const val LFH_FILENAME_LENGTH_OFFSET = 26
        private const val LFH_EXTRA_LENGTH_OFFSET = 28
        private const val LFH_FILENAME_OFFSET = 30
        private const val CFH_SIG_OFFSET = 0
        private const val CFH_VERSION_MADE_BY_OFFSET = 4
        private const val CFH_VERSION_NEEDED_OFFSET = 6
        private const val CFH_GPB_OFFSET = 8
        private const val CFH_METHOD_OFFSET = 10
        private const val CFH_TIME_OFFSET = 12
        private const val CFH_CRC_OFFSET = 16
        private const val CFH_COMPRESSED_SIZE_OFFSET = 20
        private const val CFH_ORIGINAL_SIZE_OFFSET = 24
        private const val CFH_FILENAME_LENGTH_OFFSET = 28
        private const val CFH_EXTRA_LENGTH_OFFSET = 30
        private const val CFH_COMMENT_LENGTH_OFFSET = 32
        private const val CFH_DISK_NUMBER_OFFSET = 34
        private const val CFH_INTERNAL_ATTRIBUTES_OFFSET = 36
        private const val CFH_EXTERNAL_ATTRIBUTES_OFFSET = 38
        private const val CFH_LFH_OFFSET = 42
        private const val CFH_FILENAME_OFFSET = 46

        /**
         * Compression method for deflated entries.
         */
        public const val DEFLATED: Int = ZipEntry.DEFLATED

        /**
         * Default compression level for deflated entries.
         */
        public const val DEFAULT_COMPRESSION: Int = Deflater.DEFAULT_COMPRESSION

        /**
         * Compression method for stored entries.
         */
        public const val STORED: Int = ZipEntry.STORED

        /**
         * default encoding for file names and comment.
         */
        public const val DEFAULT_ENCODING: String = ZipEncodingHelper.UTF8

        private val EMPTY = ByteArray(0)

        /**
         * Helper, a 0 as ZipShort.
         */
        private val ZERO = byteArrayOf(0, 0)

        /**
         * Helper, a 0 as ZipLong.
         */
        private val LZERO = byteArrayOf(0, 0, 0, 0)
        private val ONE = ZipLong.getBytes(1L)

        /*
         * Various ZIP constants shared between this class, ZipArchiveInputStream and ZipFile
         */

        /**
         * local file header signature
         */
        @JvmField
        public val LFH_SIG: ByteArray = ZipLong.LFH_SIG.bytes

        /**
         * data descriptor signature
         */
        @JvmField
        public val DD_SIG: ByteArray = ZipLong.DD_SIG.bytes

        /**
         * central file header signature
         */
        public val CFH_SIG: ByteArray = ZipLong.CFH_SIG.bytes

        /**
         * end of central dir signature
         */
        @JvmField
        public val EOCD_SIG: ByteArray = ZipLong.getBytes(0X06054B50L)

        /**
         * ZIP64 end of central dir signature
         */
        @JvmField
        public val ZIP64_EOCD_SIG: ByteArray = ZipLong.getBytes(0X06064B50L)

        /**
         * ZIP64 end of central dir locator signature
         */
        @JvmField
        public val ZIP64_EOCD_LOC_SIG: ByteArray = ZipLong.getBytes(0X07064B50L)
    }
}
