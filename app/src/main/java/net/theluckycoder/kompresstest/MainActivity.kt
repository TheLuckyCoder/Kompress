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
                    GlobalScope.launch(Dispatchers.Main.immediate) {
                        val unzipResult = unzip(uri)
                        findViewById<TextView>(R.id.tvResult).text = unzipResult
                    }
                }
            }
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    private suspend fun unzip(fileUri: Uri): String = withContext(Dispatchers.IO) {
        try {
            filesDir.deleteRecursively()

            val name = fileUri.readDisplayName()
            val newPackFile = File(filesDir, name!!)

            contentResolver.openInputStream(fileUri)
                ?.copyTo(newPackFile.outputStream(), DEFAULT_BUFFER_SIZE * 8)

            val unzippedFolder = File(filesDir, "unzipped/")

            unzippedFolder.deleteRecursively()
            ZipExample.unzipUsingZipFile(newPackFile, unzippedFolder)

            unzippedFolder.deleteRecursively()
            ZipExample.unzipUsingZipArchiveStream(newPackFile, unzippedFolder)

            ZipExample.unzipAsync(Dispatchers.Default, newPackFile, unzippedFolder)

            ZipExample.filesToZipAsync(Dispatchers.Default, unzippedFolder, File(filesDir, "result.zip"))
            "All Tests passed successfully on this zip file"
        } catch (e: Exception) {
            e.stackTraceToString()
        }
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