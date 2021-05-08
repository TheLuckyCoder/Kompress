package net.theluckycoder.kompress.utils

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

internal fun File.inputFileChannel(): FileChannel = inputStream().channel

internal fun File.outputFileChannel(): FileChannel = outputStream().channel

internal fun File.randomAccessFileChannel(mode: String = "r"): FileChannel = RandomAccessFile(this, mode).channel
