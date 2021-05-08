package net.theluckycoder.kompress.utils

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel

@JvmOverloads
internal fun InputStream.readFully(array: ByteArray, offset: Int = 0, len: Int = array.size): Int {
    if (len < 0 || offset < 0 || len + offset > array.size) {
        throw IndexOutOfBoundsException()
    }
    var count = 0
    var x: Int
    while (count != len) {
        x = read(array, offset + count, len - count)
        if (x == -1) {
            break
        }
        count += x
    }
    return count
}

@Throws(IOException::class)
internal fun ReadableByteChannel.readFully(buffer: ByteBuffer) {
    val expectedLength = buffer.remaining()
    var read = 0
    while (read < expectedLength) {
        val readNow = read(buffer)
        if (readNow <= 0) {
            break
        }
        read += readNow
    }
    if (read < expectedLength) {
        throw EOFException()
    }
}

internal fun Closeable?.closeQuietly() {
    if (this != null) {
        try {
            close()
        } catch (_: IOException) {
        }
    }
}
