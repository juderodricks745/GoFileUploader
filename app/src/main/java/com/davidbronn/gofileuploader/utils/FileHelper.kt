package com.davidbronn.gofileuploader.utils

import android.app.Activity
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.davidbronn.gofileuploader.BuildConfig
import com.davidbronn.gofileuploader.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

fun currentMilli(extension: String) = "${System.currentTimeMillis()}.$extension"

/**
 * Deletes the file path passed as String
 */
fun String.delete() {
    File(this).run {
        if (exists()) {
            delete()
        }
    }
}

/**
 * Returns a timeStamped file in the getExternal directories, File folder
 */
fun Activity.getFile(fileNameWithExtension: String, folderName: String): File? {
    val folder = File(getExternalFilesDir(null), folderName)
    if (!folder.exists()) {
        folder.mkdirs()
    }
    return File(folder, fileNameWithExtension)
}

/**
 * Gets the URI for the file
 */
fun Activity.getUriForFile(file: File): Uri {
    return FileProvider.getUriForFile(
        this,
        BuildConfig.APPLICATION_ID + getString(R.string.file_provider_name),
        file
    )
}

suspend fun Activity.copyDocument(uri: Uri, body: (File) -> Unit) {
    withContext(Dispatchers.IO) {
        val documentFile = DocumentFile.fromSingleUri(this@copyDocument, uri)
        documentFile?.let { document ->
            var outputStream: OutputStream?
            val inputStream = contentResolver.openInputStream(documentFile.uri)
            inputStream?.let {
                // For custom document file name, change *document.name below*
                val file = getFile(document.name!!, DOCUMENT_FOLDER)
                outputStream = FileOutputStream(file!!)
                val buf = ByteArray(1024)
                var len: Int
                while (inputStream.read(buf).also { len = it } > 0) {
                    outputStream?.write(buf, 0, len)
                }
                outputStream?.close()
                inputStream.close()
                body(file)
            }
        }
    }
}

const val EXT_JPG = ".jpg"
const val IMAGES_FOLDER = "Images"
const val DOCUMENT_FOLDER = "Documents"

