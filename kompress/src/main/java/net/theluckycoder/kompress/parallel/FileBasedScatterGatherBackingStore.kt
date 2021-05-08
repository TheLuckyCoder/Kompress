package net.theluckycoder.kompress.parallel

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * ScatterGatherBackingStore that is backed by a file.
 */
public class FileBasedScatterGatherBackingStore(private val target: File) : ScatterGatherBackingStore {

    private val os: OutputStream = target.outputStream()
    private var closed = false

    override val inputStream: InputStream = target.inputStream()

    @Throws(IOException::class)
    override fun closeForWriting() {
        if (!closed) {
            os.close()
            closed = true
        }
    }

    @Throws(IOException::class)
    override fun writeOut(data: ByteArray, offset: Int, length: Int) {
        os.write(data, offset, length)
    }

    override fun close() {
        try {
            closeForWriting()
        } finally {
            if (target.exists() && !target.delete())
                target.deleteOnExit()
        }
    }
}
