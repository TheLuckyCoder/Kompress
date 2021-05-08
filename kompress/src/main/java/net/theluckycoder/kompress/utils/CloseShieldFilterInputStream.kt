package net.theluckycoder.kompress.utils

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Re-implements [FilterInputStream.close] to do nothing.
 */
internal class CloseShieldFilterInputStream(inputStream: InputStream?) : FilterInputStream(inputStream) {

    override fun close(): Unit = Unit
}
