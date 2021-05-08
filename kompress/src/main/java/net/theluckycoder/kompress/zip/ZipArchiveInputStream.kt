package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.archivers.ArchiveEntry
import net.theluckycoder.kompress.archivers.ArchiveInputStream
import net.theluckycoder.kompress.utils.InputStreamStatistics
import net.theluckycoder.kompress.utils.readFully
import net.theluckycoder.kompress.utils.sanitize
import java.io.*
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.zip.*
import kotlin.math.abs
import kotlin.math.min

/**
 * Implements an input stream that can read Zip archives.
 *
 *
 * As of Apache Commons Compress it transparently supports Zip64
 * extensions and thus individual entries and archives larger than 4
 * GB or with more than 65536 entries.
 *
 *
 * The [ZipFile] class is preferred when reading from files
 * as [ZipArchiveInputStream] is limited by not being able to
 * read the central directory header before returning entries.
 *
 *
 *
 *  * may return entries that are not part of the central directory
 * at all and shouldn't be considered part of the archive.
 *
 *  * may return several entries with the same name.
 *
 *  * will not return internal or external attributes.
 *
 *  * may return incomplete extra field data.
 *
 *  * may return unknown sizes and CRC values for entries until the
 * next entry has been reached if the archive uses the data
 * descriptor feature.
 *
 *
 * @see ZipFile
 *
 * @NotThreadSafe
 */
public class ZipArchiveInputStream
/**
 * Create an instance using the specified encoding
 * @param inputStream the stream to wrap
 * @param encoding the encoding to use for file names, use null
 * for the platform's default encoding
 * @param useUnicodeExtraFields whether to use InfoZIP Unicode
 * Extra Fields (if present) to set the file names.
 * @param allowStoredEntriesWithDataDescriptor whether the stream
 * will try to read STORED entries that use a data descriptor
 * @param skipSplitSig Whether the stream will try to skip the zip
 * split signature(08074B50) at the beginning. You will need to
 * set this to true if you want to read a split archive.
 */
@JvmOverloads
constructor(
    inputStream: InputStream?,
    // the provided encoding (for unit tests)
    public val encoding: String = ZipEncodingHelper.UTF8,
    /** Whether to look for and use Unicode extra fields.  */
    private val useUnicodeExtraFields: Boolean = true,
    allowStoredEntriesWithDataDescriptor: Boolean = false,
    skipSplitSig: Boolean = false
) : ArchiveInputStream(), InputStreamStatistics {

    /** The zip encoding to use for file names and the file comment.  */
    private val zipEncoding: ZipEncoding = ZipEncodingHelper.getZipEncoding(encoding)

    /** Wrapped stream, will always be a PushbackInputStream.  */
    private val input: InputStream

    /** Inflater used for all deflated entries.  */
    private val inf = Inflater(true)

    /** Buffer used to read from the wrapped stream.  */
    private val buf = ByteBuffer.allocate(ZipArchiveOutputStream.BUFFER_SIZE)

    /** The entry that is currently being read.  */
    private var current: CurrentEntry? = null

    /** Whether the stream has been closed.  */
    private var closed = false

    /** Whether the stream has reached the central directory - and thus found all entries.  */
    private var hitCentralDirectory = false

    /**
     * When reading a stored entry that uses the data descriptor this
     * stream has to read the full entry and caches it.  This is the
     * cache.
     */
    private var lastStoredEntry: ByteArrayInputStream? = null

    /** Whether the stream will try to read STORED entries that use a data descriptor.  */
    private var allowStoredEntriesWithDataDescriptor = false

    /** Count decompressed bytes for current entry  */
    override var uncompressedCount: Long = 0L
        private set

    /** Whether the stream will try to skip the zip split signature(08074B50) at the beginning  */
    private val skipSplitSig: Boolean

    // cached buffers - must only be used locally in the class (COMPRESS-172 - reduce garbage collection)
    private val lfhBuf = ByteArray(LFH_LEN)
    private val skipBuf = ByteArray(1024)
    private val shortBuf = ByteArray(ZipConstants.SHORT)
    private val wordBuf = ByteArray(ZipConstants.WORD)
    private val twoDwordBuf = ByteArray(2 * ZipConstants.DWORD)
    private var entriesRead = 0

    init {
        input = PushbackInputStream(inputStream, buf.capacity())
        this.allowStoredEntriesWithDataDescriptor = allowStoredEntriesWithDataDescriptor
        this.skipSplitSig = skipSplitSig
        // haven't read anything so far
        buf.limit(0)
    }

    // assignment as documentation
    // split archives have a special signature before the
    // first local file header - look for it and fail with
    // the appropriate error message if this is a split
    // archive.
    override fun getNextEntry(): ArchiveEntry? {
        uncompressedCount = 0
        var firstEntry = true
        if (closed || hitCentralDirectory) {
            return null
        }
        if (current != null) {
            closeEntry()
            firstEntry = false
        }
        val currentHeaderOffset = bytesRead
        try {
            if (firstEntry) {
                // split archives have a special signature before the
                // first local file header - look for it and fail with
                // the appropriate error message if this is a split
                // archive.
                readFirstLocalFileHeader(lfhBuf)
            } else {
                readFully(lfhBuf)
            }
        } catch (e: EOFException) {
            return null
        }

        val sig = ZipLong(lfhBuf)
        if (sig != ZipLong.LFH_SIG) {
            if (sig == ZipLong.CFH_SIG || sig == ZipLong.AED_SIG || isApkSigningBlock(lfhBuf)) {
                hitCentralDirectory = true
                skipRemainderOfArchive()
                return null
            }
            throw ZipException(String.format("Unexpected record signature: 0X%X", sig.value))
        }
        var off = ZipConstants.WORD
        val current = CurrentEntry()
        this.current = current

        val versionMadeBy = ZipShort.getValue(lfhBuf, off)
        off += ZipConstants.SHORT
        current.entry.platform = versionMadeBy shr ZipFile.BYTE_SHIFT and ZipFile.NIBLET_MASK
        val gpFlag = GeneralPurposeBit.parse(lfhBuf, off)
        val hasUTF8Flag = gpFlag.usesUTF8ForNames()
        val entryEncoding = if (hasUTF8Flag) ZipEncodingHelper.UTF8_ZIP_ENCODING else zipEncoding
        current.hasDataDescriptor = gpFlag.usesDataDescriptor()
        current.entry.generalPurposeBit = gpFlag
        off += ZipConstants.SHORT
        current.entry.method = ZipShort.getValue(lfhBuf, off)
        off += ZipConstants.SHORT
        val time = ZipUtil.dosToJavaTime(ZipLong.getValue(lfhBuf, off))
        current.entry.time = time
        off += ZipConstants.WORD
        var size: ZipLong? = null
        var cSize: ZipLong? = null
        if (!current.hasDataDescriptor) {
            current.entry.crc = ZipLong.getValue(lfhBuf, off)
            off += ZipConstants.WORD
            cSize = ZipLong(lfhBuf, off)
            off += ZipConstants.WORD
            size = ZipLong(lfhBuf, off)
            off += ZipConstants.WORD
        } else {
            off += 3 * ZipConstants.WORD
        }

        val fileNameLen = ZipShort.getValue(lfhBuf, off)
        off += ZipConstants.SHORT
        val extraLen = ZipShort.getValue(lfhBuf, off)
        off += ZipConstants.SHORT // assignment as documentation
        val fileName = ByteArray(fileNameLen)
        readFully(fileName)
        current.entry.setName(entryEncoding.decode(fileName), fileName)
        if (hasUTF8Flag)
            current.entry.nameSource = ZipArchiveEntry.NameSource.NAME_WITH_EFS_FLAG

        val extraData = ByteArray(extraLen)
        readFully(extraData)
        current.entry.extra = extraData
        if (!hasUTF8Flag && useUnicodeExtraFields)
            ZipUtil.setNameAndCommentFromExtraFields(current.entry, fileName, null)

        processZip64Extra(size, cSize)
        current.entry.localHeaderOffset = currentHeaderOffset
        current.entry.dataOffset = bytesRead
        current.entry.isStreamContiguous = true

        val method = ZipMethod.getMethodByCode(current.entry.method)
        if (current.entry.compressedSize != ArchiveEntry.SIZE_UNKNOWN) {
            if (ZipUtil.canHandleEntryData(current.entry) && method != ZipMethod.STORED && method != ZipMethod.DEFLATED) {
                val bis: InputStream = BoundedInputStream(input, current.entry.compressedSize)
                when (method) {
                    ZipMethod.UNSHRINKING -> current.inputStream = UnshrinkingInputStream(bis)
                    ZipMethod.IMPLODING -> current.inputStream = ExplodingInputStream(
                        current.entry.generalPurposeBit.slidingDictionarySize,
                        current.entry.generalPurposeBit.numberOfShannonFanoTrees,
                        bis
                    )
                    ZipMethod.BZIP2 -> Unit
                    ZipMethod.ENHANCED_DEFLATED -> Unit
                    else -> Unit
                }
            }
        }
        entriesRead++
        return current.entry
    }

    /**
     * Fills the given array with the first local file header and
     * deals with splitting/spanning markers that may prefix the first
     * LFH.
     */
    @Throws(IOException::class)
    private fun readFirstLocalFileHeader(lfh: ByteArray) {
        readFully(lfh)
        val sig = ZipLong(lfh)
        if (!skipSplitSig && sig == ZipLong.DD_SIG) {
            throw UnsupportedZipFeatureException(UnsupportedZipFeatureException.Feature.SPLITTING)
        }

        // the split zip signature(08074B50) should only be skipped when the skipSplitSig is set
        if (sig == ZipLong.SINGLE_SEGMENT_SPLIT_MARKER || sig == ZipLong.DD_SIG) {
            // Just skip over the marker.
            val missedLfhBytes = ByteArray(4)
            readFully(missedLfhBytes)
            System.arraycopy(lfh, 4, lfh, 0, LFH_LEN - 4)
            System.arraycopy(missedLfhBytes, 0, lfh, LFH_LEN - 4, 4)
        }
    }

    /**
     * Records whether a Zip64 extra is present and sets the size
     * information from it if sizes are 0xFFFFFFFF and the entry
     * doesn't use a data descriptor.
     */
    private fun processZip64Extra(size: ZipLong?, cSize: ZipLong?) {
        val extra = current?.entry?.getExtraField(Zip64ExtendedInformationExtraField.HEADER_ID)
        if (extra != null && extra !is Zip64ExtendedInformationExtraField)
            throw ZipException("archive contains unparseable zip64 extra field")

        val z64 = extra as? Zip64ExtendedInformationExtraField?
        val currentEntry = current!!
        currentEntry.usesZip64 = z64 != null
        if (!currentEntry.hasDataDescriptor) {
            if (z64 != null // same as current.usesZip64 but avoids NPE warning
                && (ZipLong.ZIP64_MAGIC == cSize || ZipLong.ZIP64_MAGIC == size)
            ) {
                if (z64.compressedSize == null || z64.size == null) {
                    // avoid NPE if it's a corrupted zip archive
                    throw ZipException("archive contains corrupted zip64 extra field")
                }
                currentEntry.entry.compressedSize = z64.compressedSize!!.longValue
                currentEntry.entry.size = z64.size!!.longValue
            } else if (cSize != null && size != null) {
                currentEntry.entry.compressedSize = cSize.value
                currentEntry.entry.size = size.value
            }
        }
    }

    /**
     * Whether this class is able to read the given entry.
     *
     *
     * May return false if it is set up to use encryption or a
     * compression method that hasn't been implemented yet.
     */
    override fun canReadEntryData(archiveEntry: ArchiveEntry?): Boolean {
        if (archiveEntry is ZipArchiveEntry) {
            return (ZipUtil.canHandleEntryData(archiveEntry)
                    && supportsDataDescriptorFor(archiveEntry)
                    && supportsCompressedSizeFor(archiveEntry))
        }
        return false
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }
        if (closed) {
            throw IOException("The stream is closed")
        }
        val current = current ?: return -1

        // avoid int overflow, check null buffer
        if (offset > buffer.size || length < 0 || offset < 0 || buffer.size - offset < length) {
            throw ArrayIndexOutOfBoundsException()
        }
        ZipUtil.checkRequestedFeatures(current.entry)
        if (!supportsDataDescriptorFor(current.entry)) {
            throw UnsupportedZipFeatureException(
                UnsupportedZipFeatureException.Feature.DATA_DESCRIPTOR,
                current.entry
            )
        }
        if (!supportsCompressedSizeFor(current.entry)) {
            throw UnsupportedZipFeatureException(
                UnsupportedZipFeatureException.Feature.UNKNOWN_COMPRESSED_SIZE,
                current.entry
            )
        }
        val read: Int = if (current.entry.method == ZipArchiveOutputStream.STORED) {
            readStored(buffer, offset, length)
        } else if (current.entry.method == ZipArchiveOutputStream.DEFLATED) {
            readDeflated(buffer, offset, length)
        } else if (current.entry.method == ZipMethod.UNSHRINKING.code ||
            current.entry.method == ZipMethod.IMPLODING.code ||
            current.entry.method == ZipMethod.ENHANCED_DEFLATED.code ||
            current.entry.method == ZipMethod.BZIP2.code
        ) {
            current.inputStream!!.read(buffer, offset, length)
        } else {
            throw UnsupportedZipFeatureException(
                ZipMethod.getMethodByCode(current.entry.method),
                current.entry
            )
        }
        if (read >= 0) {
            current.crc.update(buffer, offset, read)
            uncompressedCount += read.toLong()
        }
        return read
    }

    override val compressedCount: Long
        get() {
            val current = current!!
            return when (current.entry.method) {
                ZipArchiveOutputStream.STORED -> current.bytesRead
                ZipArchiveOutputStream.DEFLATED -> bytesInflated
                ZipMethod.UNSHRINKING.code -> (current.inputStream as UnshrinkingInputStream).compressedCount
                ZipMethod.IMPLODING.code -> (current.inputStream as ExplodingInputStream).compressedCount
                ZipMethod.ENHANCED_DEFLATED.code -> {
                    -1 // TODO ((Deflate64CompressorInputStream) current.in).getCompressedCount();
                }
                ZipMethod.BZIP2.code -> {
                    -1 // TODO ((BZip2CompressorInputStream) current.in).getCompressedCount();
                }
                else -> -1
            }
        }

    /**
     * Implementation of read for STORED entries.
     */
    @Throws(IOException::class)
    private fun readStored(buffer: ByteArray, offset: Int, length: Int): Int {
        val current = current!!
        if (current.hasDataDescriptor) {
            if (lastStoredEntry == null)
                readStoredEntry()

            return lastStoredEntry!!.read(buffer, offset, length)
        }
        val csize = current.entry.size
        if (current.bytesRead >= csize)
            return -1

        if (buf.position() >= buf.limit()) {
            buf.position(0)
            val l = input.read(buf.array())
            if (l == -1) {
                buf.limit(0)
                throw IOException("Truncated ZIP file")
            }
            buf.limit(l)
            count(l)
            current.bytesReadFromStream += l.toLong()
        }

        var toRead = min(buf.remaining(), length)
        if (csize - current.bytesRead < toRead) {
            // if it is smaller than toRead then it fits into an int
            toRead = (csize - current.bytesRead).toInt()
        }
        buf[buffer, offset, toRead]
        current.bytesRead += toRead.toLong()

        return toRead
    }

    /**
     * Implementation of read for DEFLATED entries.
     */
    @Throws(IOException::class)
    private fun readDeflated(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = readFromInflater(buffer, offset, length)
        if (read <= 0) {
            when {
                inf.finished() -> {
                    return -1
                }
                inf.needsDictionary() -> {
                    throw ZipException(
                        "This archive needs a preset dictionary"
                                + " which is not supported by Commons"
                                + " Compress."
                    )
                }
                read == -1 -> throw IOException("Truncated ZIP file")
            }
        }
        return read
    }

    /**
     * Potentially reads more bytes to fill the inflater's buffer and
     * reads from it.
     */
    @Throws(IOException::class)
    private fun readFromInflater(buffer: ByteArray, offset: Int, length: Int): Int {
        var read = 0
        do {
            if (inf.needsInput()) {
                val l = fill()
                if (l > 0) {
                    current!!.bytesReadFromStream += buf.limit().toLong()
                } else return if (l == -1) {
                    -1
                } else {
                    break
                }
            }
            read = try {
                inf.inflate(buffer, offset, length)
            } catch (e: DataFormatException) {
                throw (ZipException(e.message).initCause(e) as IOException)
            }
        } while (read == 0 && inf.needsInput())
        return read
    }

    @Throws(IOException::class)
    override fun close() {
        if (!closed) {
            closed = true
            try {
                input.close()
            } finally {
                inf.end()
            }
        }
    }

    /**
     * Skips over and discards value bytes of data from this input
     * stream.
     *
     *
     * This implementation may end up skipping over some smaller
     * number of bytes, possibly 0, if and only if it reaches the end
     * of the underlying stream.
     *
     *
     * The actual number of bytes skipped is returned.
     *
     * @param value the number of bytes to be skipped.
     * @return the actual number of bytes skipped.
     * @throws IOException - if an I/O error occurs.
     */
    @Throws(IOException::class)
    override fun skip(value: Long): Long {
        require(value >= 0)
        var skipped: Long = 0
        while (skipped < value) {
            val rem = value - skipped
            val x = read(skipBuf, 0, (if (skipBuf.size > rem) rem else skipBuf.size).toInt())
            if (x == -1)
                return skipped

            skipped += x.toLong()
        }
        return skipped
    }

    /**
     * Closes the current ZIP archive entry and positions the underlying
     * stream to the beginning of the next entry. All per-entry variables
     * and data structures are cleared.
     *
     *
     * If the compressed size of this entry is included in the entry header,
     * then any outstanding bytes are simply skipped from the underlying
     * stream without uncompressing them. This allows an entry to be safely
     * closed even if the compression method is unsupported.
     *
     *
     * In case we don't know the compressed size of this entry or have
     * already buffered too much data from the underlying stream to support
     * decompression, then the decompression process is completed and the
     * end position of the stream is adjusted based on the result of that
     * process.
     *
     * @throws IOException if an error occurs
     */
    @Throws(IOException::class)
    private fun closeEntry() {
        if (closed) {
            throw IOException("The stream is closed")
        }
        val current = current ?: return

        // Ensure all entry bytes are read
        if (currentEntryHasOutstandingBytes()) {
            drainCurrentEntryData()
        } else {
            // this is guaranteed to exhaust the stream
            skip(Long.MAX_VALUE)
            val inB =
                if (current.entry.method == ZipArchiveOutputStream.DEFLATED) bytesInflated else current.bytesRead

            // this is at most a single read() operation and can't
            // exceed the range of int
            val diff = (current.bytesReadFromStream - inB).toInt()

            // Pushback any required bytes
            if (diff > 0) {
                pushBack(buf.array(), buf.limit() - diff, diff)
                current.bytesReadFromStream -= diff.toLong()
            }

            // Drain remainder of entry if not all data bytes were required
            if (currentEntryHasOutstandingBytes()) {
                drainCurrentEntryData()
            }
        }
        if (lastStoredEntry == null && current.hasDataDescriptor) {
            readDataDescriptor()
        }
        inf.reset()
        buf.clear().flip()
        this.current = null
        lastStoredEntry = null
    }

    /**
     * If the compressed size of the current entry is included in the entry header
     * and there are any outstanding bytes in the underlying stream, then
     * this returns true.
     *
     * @return true, if current entry is determined to have outstanding bytes, false otherwise
     */
    private fun currentEntryHasOutstandingBytes(): Boolean {
        val current = current!!
        return current.bytesReadFromStream <= current.entry.compressedSize && !current.hasDataDescriptor
    }

    /**
     * Read all data of the current entry from the underlying stream
     * that hasn't been read, yet.
     */
    @Throws(IOException::class)
    private fun drainCurrentEntryData() {
        val current = current!!
        var remaining = current.entry.compressedSize - current.bytesReadFromStream
        while (remaining > 0) {
            val n = input.read(buf.array(), 0, min(buf.capacity().toLong(), remaining).toInt()).toLong()

            if (n < 0)
                throw EOFException("Truncated ZIP entry: ${current.entry.name.sanitize()}")

            count(n)
            remaining -= n
        }
    }

    /**
     * Get the number of bytes Inflater has actually processed.
     *
     *
     * for Java &lt; Java7 the getBytes* methods in
     * Inflater/Deflater seem to return unsigned ints rather than
     * longs that start over with 0 at 2^32.
     *
     *
     * The stream knows how many bytes it has read, but not how
     * many the Inflater actually consumed - it should be between the
     * total number of bytes read for the entry and the total number
     * minus the last read operation.  Here we just try to make the
     * value close enough to the bytes we've read by assuming the
     * number of bytes consumed must be smaller than (or equal to) the
     * number of bytes read but not smaller by more than 2^32.
     */
    private val bytesInflated: Long
        get() {
            val current = current!!
            var inB = inf.bytesRead
            if (current.bytesReadFromStream >= TWO_EXP_32) {
                while (inB + TWO_EXP_32 <= current.bytesReadFromStream) {
                    inB += TWO_EXP_32
                }
            }
            return inB
        }

    @Throws(IOException::class)
    private fun fill(): Int {
        if (closed)
            throw IOException("The stream is closed")

        val length = input.read(buf.array())
        if (length > 0) {
            buf.limit(length)
            count(buf.limit())
            inf.setInput(buf.array(), 0, buf.limit())
        }

        return length
    }

    @Throws(IOException::class)
    private fun readFully(b: ByteArray, off: Int = 0) {
        val len = b.size - off
        val count = input.readFully(b, off, len)
        count(count)

        if (count < len) {
            throw EOFException()
        }
    }

    @Throws(IOException::class)
    private fun readDataDescriptor() {
        readFully(wordBuf)
        var value = ZipLong(wordBuf)
        if (ZipLong.DD_SIG == value) {
            // data descriptor with signature, skip sig
            readFully(wordBuf)
            value = ZipLong(wordBuf)
        }
        val current = current!!
        current.entry.crc = value.value

        // if there is a ZIP64 extra field, sizes are eight bytes
        // each, otherwise four bytes each.  Unfortunately some
        // implementations - namely Java7 - use eight bytes without
        // using a ZIP64 extra field -
        // https://bugs.sun.com/bugdatabase/view_bug.do?bug_id=7073588

        // just read 16 bytes and check whether bytes nine to twelve
        // look like one of the signatures of what could follow a data
        // descriptor (ignoring archive decryption headers for now).
        // If so, push back eight bytes and assume sizes are four
        // bytes, otherwise sizes are eight bytes each.
        readFully(twoDwordBuf)
        val potentialSig = ZipLong(twoDwordBuf, ZipConstants.DWORD)
        if (potentialSig == ZipLong.CFH_SIG || potentialSig == ZipLong.LFH_SIG) {
            pushBack(twoDwordBuf, ZipConstants.DWORD, ZipConstants.DWORD)
            current.entry.compressedSize = ZipLong.getValue(twoDwordBuf)
            current.entry.size = ZipLong.getValue(twoDwordBuf, ZipConstants.WORD)
        } else {
            current.entry.compressedSize =
                ZipEightByteInteger.getLongValue(twoDwordBuf)
            current.entry.size = ZipEightByteInteger.getLongValue(twoDwordBuf, ZipConstants.DWORD)
        }
    }

    /**
     * Whether this entry requires a data descriptor this library can work with.
     *
     * @return true if allowStoredEntriesWithDataDescriptor is true,
     * the entry doesn't require any data descriptor or the method is
     * DEFLATED or ENHANCED_DEFLATED.
     */
    private fun supportsDataDescriptorFor(entry: ZipArchiveEntry): Boolean {
        return !entry.generalPurposeBit.usesDataDescriptor()
                || allowStoredEntriesWithDataDescriptor && entry.method == ZipEntry.STORED
                || entry.method == ZipEntry.DEFLATED || entry.method == ZipMethod.ENHANCED_DEFLATED.code
    }

    /**
     * Whether the compressed size for the entry is either known or
     * not required by the compression method being used.
     */
    private fun supportsCompressedSizeFor(entry: ZipArchiveEntry): Boolean {
        return entry.compressedSize != ArchiveEntry.SIZE_UNKNOWN || entry.method == ZipEntry.DEFLATED || entry.method == ZipMethod.ENHANCED_DEFLATED.code || (entry.generalPurposeBit.usesDataDescriptor()
                && allowStoredEntriesWithDataDescriptor
                && entry.method == ZipEntry.STORED)
    }

    /**
     * Caches a stored entry that uses the data descriptor.
     *
     *
     *  * Reads a stored entry until the signature of a local file
     * header, central directory header or data descriptor has been
     * found.
     *  * Stores all entry data in lastStoredEntry.
     *  * Rewinds the stream to position at the data
     * descriptor.
     *  * reads the data descriptor
     *
     *
     *
     * After calling this method the entry should know its size,
     * the entry's data is cached and the stream is positioned at the
     * next local file or central directory header.
     */
    @Throws(IOException::class)
    private fun readStoredEntry() {
        val bos = ByteArrayOutputStream()
        var off = 0
        var done = false

        // length of DD without signature
        val current = current!!
        val ddLen =
            if (current.usesZip64) ZipConstants.WORD + 2 * ZipConstants.DWORD else 3 * ZipConstants.WORD
        while (!done) {
            val r = input.read(buf.array(), off, ZipArchiveOutputStream.BUFFER_SIZE - off)
            if (r <= 0) {
                // read the whole archive without ever finding a
                // central directory
                throw IOException("Truncated ZIP file")
            }
            if (r + off < 4) {
                // buffer too small to check for a signature, loop
                off += r
                continue
            }
            done = bufferContainsSignature(bos, off, r, ddLen)
            if (!done) {
                off = cacheBytesRead(bos, off, r, ddLen)
            }
        }
        if (current.entry.compressedSize != current.entry.size) {
            throw ZipException(
                "compressed and uncompressed size don't match"
                        + USE_ZIPFILE_INSTEAD_OF_STREAM_DISCLAIMER
            )
        }
        val b = bos.toByteArray()
        if (b.size.toLong() != current.entry.size) {
            throw ZipException(
                "actual and claimed size don't match"
                        + USE_ZIPFILE_INSTEAD_OF_STREAM_DISCLAIMER
            )
        }
        lastStoredEntry = ByteArrayInputStream(b)
    }

    /**
     * Checks whether the current buffer contains the signature of a
     * &quot;data descriptor&quot;, &quot;local file header&quot; or
     * &quot;central directory entry&quot;.
     *
     *
     * If it contains such a signature, reads the data descriptor
     * and positions the stream right after the data descriptor.
     */
    @Throws(IOException::class)
    private fun bufferContainsSignature(
        bos: ByteArrayOutputStream,
        offset: Int,
        lastRead: Int,
        expectedDDLen: Int
    ): Boolean {
        var done = false
        var i = 0
        while (!done && i < offset + lastRead - 4) {
            if (buf.array()[i] == LFH[0] && buf.array()[i + 1] == LFH[1]) {
                var expectDDPos = i
                if (i >= expectedDDLen &&
                    buf.array()[i + 2] == LFH[2] && buf.array()[i + 3] == LFH[3]
                    || buf.array()[i] == CFH[2] && buf.array()[i + 3] == CFH[3]
                ) {
                    // found a LFH or CFH:
                    expectDDPos = i - expectedDDLen
                    done = true
                } else if (buf.array()[i + 2] == DD[2] && buf.array()[i + 3] == DD[3]) {
                    // found DD:
                    done = true
                }
                if (done) {
                    // * push back bytes read in excess as well as the data
                    //   descriptor
                    // * copy the remaining bytes to cache
                    // * read data descriptor
                    pushBack(buf.array(), expectDDPos, offset + lastRead - expectDDPos)
                    bos.write(buf.array(), 0, expectDDPos)
                    readDataDescriptor()
                }
            }
            i++
        }
        return done
    }

    /**
     * If the last read bytes could hold a data descriptor and an
     * incomplete signature then save the last bytes to the front of
     * the buffer and cache everything in front of the potential data
     * descriptor into the given ByteArrayOutputStream.
     *
     *
     * Data descriptor plus incomplete signature (3 bytes in the
     * worst case) can be 20 bytes max.
     */
    private fun cacheBytesRead(
        bos: ByteArrayOutputStream,
        offset: Int,
        lastRead: Int,
        expecteDDLen: Int
    ): Int {
        var newOffset = offset
        val cacheable = newOffset + lastRead - expecteDDLen - 3
        if (cacheable > 0) {
            bos.write(buf.array(), 0, cacheable)
            System.arraycopy(buf.array(), cacheable, buf.array(), 0, expecteDDLen + 3)
            newOffset = expecteDDLen + 3
        } else {
            newOffset += lastRead
        }
        return newOffset
    }

    @Throws(IOException::class)
    private fun pushBack(buf: ByteArray, offset: Int, length: Int) {
        (input as PushbackInputStream).unread(buf, offset, length)
        pushedBackBytes(length.toLong())
    }
    // End of Central Directory Record
    //   end of central dir signature    WORD
    //   number of this disk             SHORT
    //   number of the disk with the
    //   start of the central directory  SHORT
    //   total number of entries in the
    //   central directory on this disk  SHORT
    //   total number of entries in
    //   the central directory           SHORT
    //   size of the central directory   WORD
    //   offset of start of central
    //   directory with respect to
    //   the starting disk number        WORD
    //   .ZIP file comment length        SHORT
    //   .ZIP file comment               up to 64KB
    //
    /**
     * Reads the stream until it find the "End of central directory
     * record" and consumes it as well.
     */
    @Throws(IOException::class)
    private fun skipRemainderOfArchive() {
        // skip over central directory. One LFH has been read too much
        // already.  The calculation discounts file names and extra
        // data so it will be too short.
        realSkip(entriesRead.toLong() * CFH_LEN - LFH_LEN)
        findEocdRecord()
        realSkip(ZipFile.MIN_EOCD_SIZE.toLong() - ZipConstants.WORD /* signature */ - ZipConstants.SHORT /* comment len */)
        readFully(shortBuf)
        // file comment
        realSkip(ZipShort.getValue(shortBuf).toLong())
    }

    /**
     * Reads forward until the signature of the &quot;End of central
     * directory&quot; record is found.
     */
    @Throws(IOException::class)
    private fun findEocdRecord() {
        var currentByte = -1
        var skipReadCall = false

        while (skipReadCall || readOneByte().also { currentByte = it } > -1) {
            skipReadCall = false
            if (!isFirstByteOfEocdSig(currentByte))
                continue

            currentByte = readOneByte()
            if (currentByte != ZipArchiveOutputStream.EOCD_SIG[1].toInt()) {
                if (currentByte == -1)
                    break

                skipReadCall = isFirstByteOfEocdSig(currentByte)
                continue
            }
            currentByte = readOneByte()
            if (currentByte != ZipArchiveOutputStream.EOCD_SIG[2].toInt()) {
                if (currentByte == -1)
                    break

                skipReadCall = isFirstByteOfEocdSig(currentByte)
                continue
            }

            currentByte = readOneByte()
            if (currentByte == -1 || currentByte == ZipArchiveOutputStream.EOCD_SIG[3].toInt())
                break

            skipReadCall = isFirstByteOfEocdSig(currentByte)
        }
    }

    /**
     * Skips bytes by reading from the underlying stream rather than
     * the (potentially inflating) archive stream - which [skip] would do.
     *
     * Also updates bytes-read counter.
     */
    @Throws(IOException::class)
    private fun realSkip(value: Long) {
        require(value >= 0)
        var skipped = 0L
        while (skipped < value) {
            val rem = value - skipped
            val x =
                input.read(skipBuf, 0, (if (skipBuf.size > rem) rem else skipBuf.size).toInt())
            if (x == -1)
                return

            count(x)
            skipped += x.toLong()
        }
    }

    /**
     * Reads bytes by reading from the underlying stream rather than
     * the (potentially inflating) archive stream - which [read] would do.
     *
     * Also updates bytes-read counter.
     */
    @Throws(IOException::class)
    private fun readOneByte(): Int {
        val b = input.read()
        if (b != -1)
            count(1)

        return b
    }

    private fun isFirstByteOfEocdSig(b: Int): Boolean {
        return b == ZipArchiveOutputStream.EOCD_SIG[0].toInt()
    }

    /**
     * Checks whether this might be an APK Signing Block.
     *
     *
     * Unfortunately the APK signing block does not start with some kind of signature, it rather ends with one. It
     * starts with a length, so what we do is parse the suspect length, skip ahead far enough, look for the signature
     * and if we've found it, return true.
     *
     * @param suspectLocalFileHeader the bytes read from the underlying stream in the expectation that they would hold
     * the local file header of the next entry.
     *
     * @return true if this looks like a APK signing block
     *
     * @see (https://source.android.com/security/apksigning/v2)
     */
    @Throws(IOException::class)
    private fun isApkSigningBlock(suspectLocalFileHeader: ByteArray): Boolean {
        // length of block excluding the size field itself
        val len = ZipEightByteInteger.getValue(suspectLocalFileHeader)
        // LFH has already been read and all but the first eight bytes contain (part of) the APK signing block,
        // also subtract 16 bytes in order to position us at the magic string
        var toSkip = len.add(
            BigInteger.valueOf(
                ZipConstants.DWORD - suspectLocalFileHeader.size
                        - APK_SIGNING_BLOCK_MAGIC.size.toLong()
            )
        )
        val magic = ByteArray(APK_SIGNING_BLOCK_MAGIC.size)
        try {
            if (toSkip.signum() < 0) {
                // suspectLocalFileHeader contains the start of suspect magic string
                val off = suspectLocalFileHeader.size + toSkip.toInt()
                // length was shorter than magic length
                if (off < ZipConstants.DWORD)
                    return false

                val bytesInBuffer = abs(toSkip.toInt())
                System.arraycopy(
                    suspectLocalFileHeader,
                    off,
                    magic,
                    0,
                    min(bytesInBuffer, magic.size)
                )
                if (bytesInBuffer < magic.size) {
                    readFully(magic, bytesInBuffer)
                }
            } else {
                while (toSkip > LONG_MAX) {
                    realSkip(Long.MAX_VALUE)
                    toSkip = toSkip.add(LONG_MAX.negate())
                }
                realSkip(toSkip.toLong())
                readFully(magic)
            }
        } catch (ex: EOFException) {
            // length was invalid
            return false
        }
        return magic.contentEquals(APK_SIGNING_BLOCK_MAGIC)
    }

    /**
     * Structure collecting information for the entry that is
     * currently being read.
     */
    private class CurrentEntry {
        /**
         * Current ZIP entry.
         */
        val entry = ZipArchiveEntry()

        /**
         * Does the entry use a data descriptor?
         */
        var hasDataDescriptor = false

        /**
         * Does the entry have a ZIP64 extended information extra field.
         */
        var usesZip64 = false

        /**
         * Number of bytes of entry content read by the client if the
         * entry is STORED.
         */
        var bytesRead: Long = 0

        /**
         * Number of bytes of entry content read from the stream.
         *
         *
         * This may be more than the actual entry's length as some
         * stuff gets buffered up and needs to be pushed back when the
         * end of the entry has been reached.
         */
        var bytesReadFromStream: Long = 0

        /**
         * The checksum calculated as the current entry is read.
         */
        val crc = CRC32()

        /**
         * The input stream decompressing the data for shrunk and imploded entries.
         */
        var inputStream: InputStream? = null
    }

    /**
     * Bounded input stream adapted from commons-io
     */
    private inner class BoundedInputStream(
        /** the wrapped input stream  */
        private val input: InputStream,
        /** the max length of bytes to return  */
        private val max: Long
    ) : InputStream() {
        /** the number of bytes already returned  */
        private var pos: Long = 0

        @Throws(IOException::class)
        override fun read(): Int {
            if (max in 0..pos)
                return -1

            val result = input.read()
            pos++
            count(1)
            current!!.bytesReadFromStream++
            return result
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray): Int {
            return this.read(b, 0, b.size)
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) {
                return 0
            }
            if (max in 0..pos) {
                return -1
            }
            val maxRead = if (max >= 0) min(len.toLong(), max - pos) else len.toLong()
            val bytesRead = input.read(b, off, maxRead.toInt())
            if (bytesRead == -1) {
                return -1
            }
            pos += bytesRead.toLong()
            count(bytesRead)
            current!!.bytesReadFromStream += bytesRead.toLong()
            return bytesRead
        }

        @Throws(IOException::class)
        override fun skip(n: Long): Long {
            val toSkip = if (max >= 0) min(n, max - pos) else n
            val skippedBytes = input.skip(toSkip)
            // TODO? val skippedBytes: Long = IOUtils.skip(input, toSkip)
            pos += skippedBytes
            return skippedBytes
        }

        @Throws(IOException::class)
        override fun available(): Int = if (max in 0..pos) 0 else input.available()
    }

    public companion object {
        private const val LFH_LEN = 30

        /*
          local file header signature     WORD
          version needed to extract       SHORT
          general purpose bit flag        SHORT
          compression method              SHORT
          last mod file time              SHORT
          last mod file date              SHORT
          crc-32                          WORD
          compressed size                 WORD
          uncompressed size               WORD
          file name length                SHORT
          extra field length              SHORT
        */
        private const val CFH_LEN = 46

        /*
            central file header signature   WORD
            version made by                 SHORT
            version needed to extract       SHORT
            general purpose bit flag        SHORT
            compression method              SHORT
            last mod file time              SHORT
            last mod file date              SHORT
            crc-32                          WORD
            compressed size                 WORD
            uncompressed size               WORD
            file name length                SHORT
            extra field length              SHORT
            file comment length             SHORT
            disk number start               SHORT
            internal file attributes        SHORT
            external file attributes        WORD
            relative offset of local header WORD
        */
        private const val TWO_EXP_32 = ZipConstants.ZIP64_MAGIC + 1

        /**
         * Checks if the signature matches what is expected for a zip file.
         * Does not currently handle self-extracting zips which may have arbitrary
         * leading content.
         *
         * @param signature the bytes to check
         * @param length    the number of bytes to check
         * @return true, if this stream is a zip archive stream, false otherwise
         */
        public fun matches(signature: ByteArray, length: Int): Boolean {
            return if (length < ZipArchiveOutputStream.LFH_SIG.size) {
                false
            } else checkSignature(
                signature,
                ZipArchiveOutputStream.LFH_SIG
            ) // normal file
                    || checkSignature(
                signature,
                ZipArchiveOutputStream.EOCD_SIG
            ) // empty zip
                    || checkSignature(
                signature,
                ZipArchiveOutputStream.DD_SIG
            ) // split zip
                    || checkSignature(
                signature,
                ZipLong.SINGLE_SEGMENT_SPLIT_MARKER.bytes
            )
        }

        private fun checkSignature(signature: ByteArray, expected: ByteArray): Boolean {
            for (i in expected.indices) {
                if (signature[i] != expected[i])
                    return false
            }
            return true
        }

        private const val USE_ZIPFILE_INSTEAD_OF_STREAM_DISCLAIMER =
            (" while reading a stored entry using data descriptor. Either the archive is broken"
                    + " or it can not be read using ZipArchiveInputStream and you must use ZipFile."
                    + " A common cause for this is a ZIP archive containing a ZIP archive."
                    + " See http://commons.apache.org/proper/commons-compress/zip.html#ZipArchiveInputStream_vs_ZipFile")
        private val LFH = ZipLong.LFH_SIG.bytes
        private val CFH = ZipLong.CFH_SIG.bytes
        private val DD = ZipLong.DD_SIG.bytes
        private val APK_SIGNING_BLOCK_MAGIC = byteArrayOf(
            'A'.code.toByte(),
            'P'.code.toByte(),
            'K'.code.toByte(),
            ' '.code.toByte(),
            'S'.code.toByte(),
            'i'.code.toByte(),
            'g'.code.toByte(),
            ' '.code.toByte(),
            'B'.code.toByte(),
            'l'.code.toByte(),
            'o'.code.toByte(),
            'c'.code.toByte(),
            'k'.code.toByte(),
            ' '.code.toByte(),
            '4'.code.toByte(),
            '2'.code.toByte()
        )
        private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    }
}
