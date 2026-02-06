package dev.brewkits.kmpworkmanager.sample.utils

import android.content.Context
import java.io.File

actual fun createDummyFiles(context: Any): Pair<String, String> {
    val androidContext = context as Context
    val filesDir = androidContext.filesDir

    // Create a dummy file for upload
    val uploadFile = File(filesDir, "upload_test.txt")
    if (!uploadFile.exists()) {
        uploadFile.writeText("This is a dummy file for upload demo.")
    }

    // Create a dummy folder with some files for compression
    val compressFolder = File(filesDir, "compress_me")
    if (!compressFolder.exists()) {
        compressFolder.mkdirs()
        File(compressFolder, "file1.txt").writeText("Content of file 1")
        File(compressFolder, "file2.txt").writeText("Content of file 2")
    }
    return Pair(uploadFile.absolutePath, compressFolder.absolutePath)
}

actual fun getDummyDownloadPath(context: Any): String {
    val androidContext = context as Context
    val filesDir = androidContext.filesDir
    return File(filesDir, "download_test.bin").absolutePath
}

actual fun getDummyUploadPath(context: Any): String {
    val androidContext = context as Context
    val filesDir = androidContext.filesDir
    return File(filesDir, "upload_test.txt").absolutePath
}

actual fun getDummyCompressionInputPath(context: Any): String {
    val androidContext = context as Context
    val filesDir = androidContext.filesDir
    return File(filesDir, "compress_me").absolutePath
}

actual fun getDummyCompressionOutputPath(context: Any): String {
    val androidContext = context as Context
    val filesDir = androidContext.filesDir
    return File(filesDir, "compressed.zip").absolutePath
}
