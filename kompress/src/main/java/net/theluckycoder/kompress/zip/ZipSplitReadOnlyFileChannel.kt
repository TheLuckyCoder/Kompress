package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.utils.MultiReadOnlyFileChannel
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * [MultiReadOnlyFileChannel] that knows what a split ZIP archive should look like.
 *
 *
 * If you want to read a split archive using [ZipFile] then create an instance of this class from the parts of
 * the archive.
 */
public class ZipSplitReadOnlyFileChannel(
    /**
     * Concatenates the given channels.
     *
     * The channels should be add in ascending order, e.g. z01,
     * z02, ... z99, zip please note that the .zip file is the last
     * segment and should be added as the last one in the channels
     *
     * @throws IOException if the first channel doesn't seem to hold
     * the beginning of a split archive
     */
    channels: List<FileChannel>
) : MultiReadOnlyFileChannel(channels) {

    private val zipSplitSignatureByteBuffer = ByteBuffer.allocate(ZIP_SPLIT_SIGNATURE_LENGTH)

    init {
        // the first split zip segment should begin with zip split signature
        assertSplitSignature(channels)
    }

    /**
     * Based on the zip specification:
     *
     *
     *
     * 8.5.3 Spanned/Split archives created using PKZIP for Windows
     * (V2.50 or greater), PKZIP Command Line (V2.50 or greater),
     * or PKZIP Explorer will include a special spanning
     * signature as the first 4 bytes of the first segment of
     * the archive.  This signature (0x08074b50) will be
     * followed immediately by the local header signature for
     * the first file in the archive.
     *
     *
     *
     * the first 4 bytes of the first zip split segment should be the zip split signature(0x08074B50)
     *
     * @param channels channels to be validated
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun assertSplitSignature(channels: List<FileChannel>) {
        val channel = channels[0]
        // the zip split file signature is at the beginning of the first split segment
        channel.position(0L)
        zipSplitSignatureByteBuffer.rewind()
        channel.read(zipSplitSignatureByteBuffer)
        val signature = ZipLong(zipSplitSignatureByteBuffer.array())
        if (signature != ZipLong.DD_SIG) {
            channel.position(0L)
            throw IOException("The first zip split segment does not begin with split zip file signature")
        }
        channel.position(0L)
    }

    public companion object {
        private const val ZIP_SPLIT_SIGNATURE_LENGTH = 4

        /**
         * Concatenates the given channels.
         *
         * @param channels the channels to concatenate, note that the LAST CHANNEL of channels should be the LAST SEGMENT(.zip)
         * and theses channels should be added in correct order (e.g. .z01, .z02... .z99, .zip)
         * @return [FileChannel] that concatenates all provided channels
         * @throws NullPointerException if channels is null
         * @throws IOException if reading channels fails
         */
        @Throws(IOException::class)
        public fun forOrderedFileChannels(vararg channels: FileChannel): FileChannel {
            return if (channels.size == 1) {
                channels[0]
            } else ZipSplitReadOnlyFileChannel(
                channels.toList()
            )
        }

        /**
         * Concatenates the given channels.
         *
         * @param lastSegmentChannel channel of the last segment of split zip segments, its extension should be .zip
         * @param channels           the channels to concatenate except for the last segment,
         * note theses channels should be added in correct order (e.g. .z01, .z02... .z99)
         * @return [FileChannel] that concatenates all provided channels
         * @throws IOException if the first channel doesn't seem to hold
         * the beginning of a split archive
         */
        @Throws(IOException::class)
        public fun forOrderedFileChannels(
            lastSegmentChannel: FileChannel,
            channels: Iterable<FileChannel>
        ): FileChannel {
            val channelsList = channels.toMutableList()
            channelsList.add(lastSegmentChannel)
            return forOrderedFileChannels(*channelsList.toTypedArray())
        }

        /**
         * Concatenates the given files.
         *
         * @param files the files to concatenate, note that the LAST FILE of files should be the LAST SEGMENT(.zip)
         * and theses files should be added in correct order (e.g. .z01, .z02... .z99, .zip)
         * @return [FileChannel] that concatenates all provided files
         * @throws IOException          if opening a channel for one of the files fails
         * @throws IOException if the first channel doesn't seem to hold
         * the beginning of a split archive
         */
        @Throws(IOException::class)
        public fun forFiles(vararg files: File): FileChannel {
            val channels = files.map { it.outputStream().channel }

            return if (channels.size == 1) channels.first() else ZipSplitReadOnlyFileChannel(channels)
        }

        /**
         * Concatenates the given files.
         *
         * @param lastSegmentFile the last segment of split zip segments, its extension should be .zip
         * @param files           the files to concatenate except for the last segment,
         * note theses files should be added in correct order (e.g. .z01, .z02... .z99)
         * @return [FileChannel] that concatenates all provided files
         * @throws IOException if the first channel doesn't seem to hold
         * the beginning of a split archive
         */
        @Throws(IOException::class)
        public fun forFiles(lastSegmentFile: File, files: Iterable<File>): FileChannel {
            val filesList = files.toMutableList()
            filesList.add(lastSegmentFile)
            return forFiles(*filesList.toTypedArray())
        }
    }
}
