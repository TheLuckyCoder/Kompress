package net.theluckycoder.kompress.zip

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Used internally by [ZipArchiveOutputStream] when creating a split archive.
 *
 * Create a split zip. If the zip file is smaller than the split size,
 * then there will only be one split zip, and its suffix is .zip,
 * otherwise the split segments should be like .z01, .z02, ... .z(N-1), .zip
 *
 * @param zipFile   the zip file to write to
 * @param splitSize the split size
 */
internal class ZipSplitOutputStream(zipFile: File, splitSize: Long) : OutputStream() {
    private var outputStream: OutputStream
    private var zipFile: File
    private val splitSize: Long
    var currentSplitSegmentIndex = 0
        private set
    var currentSplitSegmentBytesWritten: Long = 0
        private set
    private var finished = false
    private val singleByte = ByteArray(1)

    init {
        require(!(splitSize < ZIP_SEGMENT_MIN_SIZE || splitSize > ZIP_SEGMENT_MAX_SIZE)) { "zip split segment size should between 64K and 4,294,967,295" }
        this.zipFile = zipFile
        this.splitSize = splitSize
        outputStream = FileOutputStream(zipFile)
        // write the zip split signature 0x08074B50 to the zip file
        writeZipSplitSignature()
    }

    /**
     * Some data can not be written to different split segments, for example:
     *
     *
     * 4.4.1.5  The end of central directory record and the Zip64 end
     * of central directory locator record MUST reside on the same
     * disk when splitting or spanning an archive.
     *
     * @param unsplittableContentSize
     * @throws IllegalArgumentException
     * @throws IOException
     */
    @Throws(IllegalArgumentException::class, IOException::class)
    fun prepareToWriteUnsplittableContent(unsplittableContentSize: Long) {
        require(unsplittableContentSize <= splitSize) { "The unsplittable content size is bigger than the split segment size" }
        val bytesRemainingInThisSegment = splitSize - currentSplitSegmentBytesWritten
        if (bytesRemainingInThisSegment < unsplittableContentSize) {
            openNewSplitSegment()
        }
    }

    @Throws(IOException::class)
    override fun write(i: Int) {
        singleByte[0] = (i and 0xff).toByte()
        write(singleByte)
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    /**
     * Write the data to zip split segments, if the remaining space of current split segment
     * is not enough, then a new split segment should be created
     *
     * @param b   data to write
     * @param off offset of the start of data in param b
     * @param len the length of data to write
     * @throws IOException
     */
    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) {
            return
        }
        when {
            currentSplitSegmentBytesWritten >= splitSize -> {
                openNewSplitSegment()
                write(b, off, len)
            }
            currentSplitSegmentBytesWritten + len > splitSize -> {
                val bytesToWriteForThisSegment = splitSize.toInt() - currentSplitSegmentBytesWritten.toInt()
                write(b, off, bytesToWriteForThisSegment)
                openNewSplitSegment()
                write(b, off + bytesToWriteForThisSegment, len - bytesToWriteForThisSegment)
            }
            else -> {
                outputStream.write(b, off, len)
                currentSplitSegmentBytesWritten += len.toLong()
            }
        }
    }

    @Throws(IOException::class)
    override fun close() {
        if (!finished) {
            finish()
        }
    }

    /**
     * The last zip split segment's suffix should be .zip
     *
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun finish() {
        if (finished) {
            throw IOException("This archive has already been finished")
        }
        val zipFileBaseName: String = zipFile.nameWithoutExtension
        val lastZipSplitSegmentFile = File(zipFile.parentFile, "$zipFileBaseName.zip")
        outputStream.close()
        if (!zipFile.renameTo(lastZipSplitSegmentFile)) {
            throw IOException("Failed to rename $zipFile to $lastZipSplitSegmentFile")
        }
        finished = true
    }

    /**
     * Create a new zip split segment and prepare to write to the new segment
     *
     * @return
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun openNewSplitSegment(): OutputStream {
        var newFile: File
        if (currentSplitSegmentIndex == 0) {
            outputStream.close()
            newFile = createNewSplitSegmentFile(1)
            if (!zipFile.renameTo(newFile)) {
                throw IOException("Failed to rename $zipFile to $newFile")
            }
        }
        newFile = createNewSplitSegmentFile(null)
        outputStream.close()
        outputStream = FileOutputStream(newFile)
        currentSplitSegmentBytesWritten = 0
        zipFile = newFile
        currentSplitSegmentIndex++
        return outputStream
    }

    /**
     * Write the zip split signature (0x08074B50) to the head of the first zip split segment
     *
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun writeZipSplitSignature() {
        outputStream.write(ZipArchiveOutputStream.DD_SIG)
        currentSplitSegmentBytesWritten += ZipArchiveOutputStream.DD_SIG.size.toLong()
    }

    /**
     * Create the new zip split segment, the last zip segment should be .zip, and the zip split segments' suffix should be
     * like .z01, .z02, .z03, ... .z99, .z100, ..., .z(N-1), .zip
     *
     *
     * 8.3.3 Split ZIP files are typically written to the same location
     * and are subject to name collisions if the spanned name
     * format is used since each segment will reside on the same
     * drive. To avoid name collisions, split archives are named
     * as follows.
     *
     *
     * Segment 1   = filename.z01
     * Segment n-1 = filename.z(n-1)
     * Segment n   = filename.zip
     *
     *
     * NOTE:
     * The zip split segment begin from 1,2,3,... , and we're creating a new segment,
     * so the new segment suffix should be (currentSplitSegmentIndex + 2)
     *
     * @param zipSplitSegmentSuffixIndex
     * @return
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun createNewSplitSegmentFile(zipSplitSegmentSuffixIndex: Int?): File {
        val newZipSplitSegmentSuffixIndex = zipSplitSegmentSuffixIndex ?: currentSplitSegmentIndex + 2
        val baseName = zipFile.nameWithoutExtension
        var extension = ".z"
        if (newZipSplitSegmentSuffixIndex <= 9) {
            extension += "0$newZipSplitSegmentSuffixIndex"
        } else {
            extension += newZipSplitSegmentSuffixIndex
        }
        val newFile = File(zipFile.parent, baseName + extension)
        if (newFile.exists()) {
            throw IOException("split zip segment $baseName$extension already exists")
        }
        return newFile
    }

    companion object {
        /**
         * 8.5.1 Capacities for split archives are as follows:
         *
         *
         * Maximum number of segments = 4,294,967,295 - 1
         * Maximum .ZIP segment size = 4,294,967,295 bytes (refer to section 8.5.6)
         * Minimum segment size = 64K
         * Maximum PKSFX segment size = 2,147,483,647 bytes
         */
        private const val ZIP_SEGMENT_MIN_SIZE = 64 * 1024L
        private const val ZIP_SEGMENT_MAX_SIZE = 4294967295L
    }
}