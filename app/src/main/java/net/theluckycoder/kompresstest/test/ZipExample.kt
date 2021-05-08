package net.theluckycoder.kompresstest.test

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import net.theluckycoder.kompress.parallel.AsyncScatterZipCreator
import net.theluckycoder.kompress.zip.ZipArchiveEntry
import net.theluckycoder.kompress.zip.ZipArchiveInputStream
import net.theluckycoder.kompress.zip.ZipArchiveOutputStream
import net.theluckycoder.kompress.zip.ZipFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import kotlin.coroutines.CoroutineContext
import kotlin.system.measureTimeMillis
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

@ExperimentalTime
object ZipExample {

    suspend fun filesToZipAsync(
        coroutineContext: CoroutineContext,
        sourceFolder: File,
        destinationFile: File
    ) = withContext(coroutineContext) {
        require(sourceFolder.isDirectory)

        if (!destinationFile.exists())
            destinationFile.createNewFile()

        val files = sourceFolder.walk()
            .filter { it.isFile && it.canRead() }
            .toList()

        val time = measureTime {
            ZipArchiveOutputStream(destinationFile.outputStream()).use { zipArchiveOutputStream ->
                val zipCreator = AsyncScatterZipCreator(coroutineContext)
                val srcFolderPathLength = sourceFolder.absolutePath.length + 1 // +1 to remove the last file separator

                files.forEach { file ->
                    val relativePath = file.absolutePath.substring(srcFolderPathLength)

                    val zipArchiveEntry = ZipArchiveEntry(relativePath)
                    zipArchiveEntry.method = ZipEntry.DEFLATED

                    zipCreator.addArchiveEntry(zipArchiveEntry) {
                        try {
                            file.inputStream()
                        } catch (e: IOException) {
                            e.printStackTrace()
                            object : InputStream() {
                                // Return an empty stream if a file failed to be read
                                override fun read(): Int = -1
                            }
                        }
                    }
                }

                zipCreator.writeTo(zipArchiveOutputStream)
            }
        }

        Log.v("Zip Time (Async)", time.toString())
        time
    }

    suspend fun unzipAsync(
        coroutineContext: CoroutineContext,
        zipFile: File,
        destinationFolder: File
    ) = withContext(coroutineContext) {
        require(zipFile.isFile)
        if (!destinationFolder.exists())
            destinationFolder.mkdirs()

        val destCanonicalPath = destinationFolder.canonicalPath

        val time = measureTime {
            ZipFile(zipFile).use { zip ->
                zip.getEntries()
                    .asSequence()
                    .filterNot { it.isDirectory } // Skip Directories
                    .filter {
                        // Make sure the entry can be read
                        // This is unnecessary if you already know that the encryption and compression methods are supported
                        zip.canReadEntryData(it)
                    }
                    .filter {
                        // This is a serious possible vulnerability
                        File(destinationFolder, it.name).canonicalPath == destCanonicalPath
                    }
                    .map { it.name to zip.getInputStream(it) }
                    .map { (entryName, inputStream) ->
                        async {
                            inputStream?.let {
                                val entryFile = File(destinationFolder, entryName)
                                entryFile.parentFile?.let { parent ->
                                    if (!parent.exists())
                                        parent.mkdirs()
                                }

                                entryFile.outputStream().use { output -> inputStream.copyTo(output) }
                            }
                            inputStream
                        }
                    }
                    .toList()
                    .awaitAll() // Wait for all files to be written
                    .forEach {
                        try {
                            it?.close() // Only close the Input Streams after all the files have been written
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
            }
        }

        Log.v("Unzip Time (Async)", time.toString())
        time
    }

    fun unzipUsingZipArchiveStream(zipFile: File, destinationFolder: File): Duration {
        require(zipFile.isFile)
        if (!destinationFolder.exists())
            destinationFolder.mkdirs()

        val time = measureTime {
            ZipArchiveInputStream(zipFile.inputStream().buffered()).use { archive ->
                while (true) {
                    val entry = archive.getNextEntry() ?: break

                    if (entry.isDirectory())
                        continue

                    val file = File(destinationFolder, entry.getName())
                    file.parentFile?.let { parent ->
                        if (!parent.exists())
                            parent.mkdirs()
                    }

                    file.outputStream().use { archive.copyTo(it) }
                }
            }
        }

        Log.v("Unzip Time (Stream)", time.toString())
        return time
    }

    fun unzipUsingZipFile(zipFile: File, destinationFolder: File): Duration {
        require(zipFile.isFile)
        if (!destinationFolder.exists())
            destinationFolder.mkdirs()

        val time = measureTime {
            ZipFile(zipFile).use { zip ->
                zip.getEntries()
                    .asSequence()
                    .filterNot { it.isDirectory }
                    .filter { zip.canReadEntryData(it) }
                    .forEach { zipArchiveEntry ->
                        zip.getInputStream(zipArchiveEntry)?.use { input ->

                            val entryFile = File(destinationFolder, zipArchiveEntry.name)
                            entryFile.parentFile?.let { parent ->
                                if (!parent.exists())
                                    parent.mkdirs()
                            }

                            entryFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
            }
        }

        Log.v("Unzip Time (File)", time.toString())
        return time
    }
}
