package net.theluckycoder.kompresstest

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.theluckycoder.kompresstest.test.ZipExample
import java.io.File
import java.lang.StringBuilder
import kotlin.time.ExperimentalTime

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        findViewById<Button>(R.id.btnChooseFile).setOnClickListener {
            showFileChooser()
        }
    }

    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "application/zip"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(
            Intent.createChooser(intent, "Select a File to Upload"),
            FILE_SELECT_CODE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        when (requestCode) {
            FILE_SELECT_CODE -> if (resultCode == RESULT_OK) {
                // Get the Uri of the selected file
                data.data?.let { uri ->
                    unzip(uri)
                }
            }
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    @OptIn(ExperimentalTime::class)
    private fun unzip(fileUri: Uri) = GlobalScope.launch(Dispatchers.Main.immediate) {
        val tvResult = findViewById<TextView>(R.id.tvResult)
        val resultString = StringBuffer()

        suspend fun displayString(append: String) {
            withContext(Dispatchers.Main.immediate) {
                resultString.append(append)
                tvResult.text = resultString.toString()
            }
        }

        displayString("Test started\n")

        val finalMessage = withContext(Dispatchers.IO) {
            try {
                filesDir.deleteRecursively()

                val name = fileUri.readDisplayName()
                val newPackFile = File(filesDir, name!!)

                contentResolver.openInputStream(fileUri)
                    ?.copyTo(newPackFile.outputStream(), DEFAULT_BUFFER_SIZE * 8)

                val unzippedFolder = File(filesDir, "unzipped/")

                unzippedFolder.deleteRecursively()
                var duration = ZipExample.unzipUsingZipFile(newPackFile, unzippedFolder)
                displayString("Unzip using ZipFile: $duration\n")

                unzippedFolder.deleteRecursively()
                duration = ZipExample.unzipUsingZipArchiveStream(newPackFile, unzippedFolder)
                displayString("Unzip using Stream: $duration\n")

                duration = ZipExample.unzipAsync(
                    Dispatchers.Default, // We don't pass Dispatchers.IO because we want to maintain a limited number of threads
                    newPackFile,
                    unzippedFolder
                )
                displayString("Unzip using ZipFile Async: $duration\n")

                duration = ZipExample.filesToZipAsync(
                    Dispatchers.Default,
                    unzippedFolder,
                    File(filesDir, "result.zip")
                )
                displayString("Unzip Async: $duration\n")

                "All Tests passed successfully on this zip file"
            } catch (e: Exception) {
                e.stackTraceToString()
            }
        }

        displayString(finalMessage)
    }

    private fun Uri.readDisplayName(): String? {
        val returnCursor =
            contentResolver.query(this, null, null, null, null) ?: return null

        val nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        returnCursor.moveToFirst()
        val fileName = returnCursor.getString(nameIndex)
        returnCursor.close()

        return fileName
    }

    private companion object {
        private const val FILE_SELECT_CODE = 100
    }
}