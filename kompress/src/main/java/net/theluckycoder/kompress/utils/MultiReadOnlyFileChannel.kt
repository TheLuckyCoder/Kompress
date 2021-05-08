package net.theluckycoder.kompress.utils

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.NonWritableChannelException
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * Read-Only Implementation of [FileChannel] that concatenates a collection of other [FileChannel]s.
 *
 *
 * This is a lose port of [MultiReadOnlySeekableByteChannel](https://github.com/frugalmechanic/fm-common/blob/master/jvm/src/main/scala/fm/common/MultiReadOnlySeekableByteChannel.scala)
 * by Tim Underwood.
 *
 * This class used to be called `MultiReadOnlySeekableByteChannel`
 */
public open class MultiReadOnlyFileChannel(channels: List<FileChannel>) : FileChannel() {
    private val channels = channels.toList()

    private var globalPosition: Long = 0L
    private var currentChannelIdx = 0

    @Synchronized
    @Throws(IOException::class)
    override fun read(dst: ByteBuffer): Int {
        if (!isOpen)
            throw ClosedChannelException()

        if (!dst.hasRemaining())
            return 0

        var totalBytesRead = 0
        while (dst.hasRemaining() && currentChannelIdx < channels.size) {
            val currentChannel = channels[currentChannelIdx]
            val newBytesRead = currentChannel.read(dst)
            if (newBytesRead == -1) {
                // EOF for this channel -- advance to next channel idx
                currentChannelIdx += 1
                continue
            }
            if (currentChannel.position() >= currentChannel.size()) {
                // we are at the end of the current channel
                currentChannelIdx++
            }
            totalBytesRead += newBytesRead
        }
        if (totalBytesRead > 0) {
            globalPosition += totalBytesRead.toLong()
            return totalBytesRead
        }
        return -1
    }

    override fun read(dsts: Array<out ByteBuffer>?, offset: Int, length: Int): Long {
        TODO("not implemented")
    }

    override fun read(dst: ByteBuffer?, position: Long): Int {
        TODO("not implemented")
    }

    @Throws(IOException::class)
    override fun implCloseChannel() {
        var first: IOException? = null
        for (ch in channels) {
            try {
                ch.close()
            } catch (ex: IOException) {
                if (first == null) {
                    first = ex
                }
            }
        }
        if (first != null) {
            throw IOException("failed to close wrapped channel", first)
        }
    }

/*TODO
    override fun isOpen(): Boolean {
        for (ch in channels) {
            if (!ch.isOpen) {
                return false
            }
        }
        return true
    }*/

    /**
     * Returns this channel's position.
     *
     *
     * This method violates the contract of [FileChannel.position] as it will not throw any exception
     * when invoked on a closed channel. Instead it will return the position the channel had when close has been
     * called.
     */
    override fun position(): Long = globalPosition

    /**
     * set the position based on the given channel number and relative offset
     *
     * @param channelNumber  the channel number
     * @param relativeOffset the relative offset in the corresponding channel
     * @return global position of all channels as if they are a single channel
     * @throws IOException if positioning fails
     */
    @Synchronized
    @Throws(IOException::class)
    public fun position(channelNumber: Long, relativeOffset: Long): FileChannel {
        if (!isOpen)
            throw ClosedChannelException()

        var globalPosition = relativeOffset
        for (i in 0 until channelNumber)
            globalPosition += channels[i.toInt()].size()

        return position(globalPosition)
    }

    @Throws(IOException::class)
    override fun size(): Long {
        if (!isOpen)
            throw ClosedChannelException()

        return channels.sumOf { it.size() }
    }

    override fun truncate(size: Long): FileChannel {
        throw NonWritableChannelException()
    }

    override fun force(metaData: Boolean) {
        throw NonWritableChannelException()
    }

    override fun transferTo(position: Long, count: Long, target: WritableByteChannel?): Long {
        throw NonWritableChannelException()
    }

    override fun transferFrom(src: ReadableByteChannel?, position: Long, count: Long): Long {
        throw NonWritableChannelException()
    }

    override fun map(mode: MapMode?, position: Long, size: Long): MappedByteBuffer {
        throw NonWritableChannelException()
    }

    override fun lock(position: Long, size: Long, shared: Boolean): FileLock {
        throw NonWritableChannelException()
    }

    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock {
        throw NonWritableChannelException()
    }

    /**
     * @throws NonWritableChannelException since this implementation is read-only.
     */
    override fun write(src: ByteBuffer): Int {
        throw NonWritableChannelException()
    }

    override fun write(srcs: Array<out ByteBuffer>?, offset: Int, length: Int): Long {
        throw NonWritableChannelException()
    }

    override fun write(src: ByteBuffer?, position: Long): Int {
        throw NonWritableChannelException()
    }

    @Synchronized
    @Throws(IOException::class)
    override fun position(newPosition: Long): FileChannel {
        require(newPosition >= 0) { "Negative position: $newPosition" }
        if (!isOpen) {
            throw ClosedChannelException()
        }
        globalPosition = newPosition
        var pos = newPosition
        for (i in channels.indices) {
            val currentChannel = channels[i]
            val size = currentChannel.size()
            val newChannelPos: Long
            when {
                pos == -1L -> {
                    // Position is already set for the correct channel,
                    // the rest of the channels get reset to 0
                    newChannelPos = 0
                }
                pos <= size -> {
                    // This channel is where we want to be
                    currentChannelIdx = i
                    val tmp = pos
                    pos = -1L // Mark pos as already being set
                    newChannelPos = tmp
                }
                else -> {
                    // newPosition is past this channel.  Set channel
                    // position to the end and subtract channel size from
                    // pos
                    pos -= size
                    newChannelPos = size
                }
            }
            currentChannel.position(newChannelPos)
        }
        return this
    }

    public companion object {
        /**
         * Concatenates the given channels.
         *
         * @param channels the channels to concatenate
         * @return FileChannel that concatenates all provided channels
         */
        public fun forFileChannels(vararg channels: FileChannel): FileChannel {
            return if (channels.size == 1) {
                channels[0]
            } else MultiReadOnlyFileChannel(
                channels.toList()
            )
        }

        /**
         * Concatenates the given files.
         *
         * @param files the files to concatenate
         * @throws IOException if opening a channel for one of the files fails
         * @return FileChannel that concatenates all provided files
         */
        @Throws(IOException::class)
        public fun forFiles(vararg files: File): FileChannel {
            val channels = files.map { it.outputFileChannel() }
            return if (channels.size == 1) channels.first() else MultiReadOnlyFileChannel(channels)
        }
    }
}
