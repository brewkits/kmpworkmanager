package dev.brewkits.kmpworkmanager.sample.utils

import platform.Foundation.*
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
actual fun createDummyFiles(context: Any): Pair<String, String> {
    val fileManager = NSFileManager.defaultManager()
    val urls = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
    val documentsDirectory = urls.first() as NSURL

    // Create a dummy file for upload
    val uploadFileURL = documentsDirectory.URLByAppendingPathComponent("upload_test.txt")
    val uploadFilePath = uploadFileURL?.path ?: ""
    val uploadFileContent = "This is a dummy file for upload demo."
    if (!fileManager.fileExistsAtPath(uploadFilePath)) {
        (uploadFileContent as NSString).writeToURL(uploadFileURL!!, true, NSUTF8StringEncoding, null)
    }

    // Create a dummy folder with some files for compression
    val compressFolderURL = documentsDirectory.URLByAppendingPathComponent("compress_me")
    val compressFolderPath = compressFolderURL?.path ?: ""
    if (!fileManager.fileExistsAtPath(compressFolderPath)) {
        fileManager.createDirectoryAtURL(compressFolderURL!!, true, null, null)
        val file1URL = compressFolderURL.URLByAppendingPathComponent("file1.txt")
        "Content of file 1".let { (it as NSString).writeToURL(file1URL!!, true, NSUTF8StringEncoding, null) }
        val file2URL = compressFolderURL.URLByAppendingPathComponent("file2.txt")
        "Content of file 2".let { (it as NSString).writeToURL(file2URL!!, true, NSUTF8StringEncoding, null) }
    }

    return Pair(uploadFilePath, compressFolderPath)
}

actual fun getDummyDownloadPath(context: Any): String {
    val fileManager = NSFileManager.defaultManager()
    val urls = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
    val documentsDirectory = urls.first() as NSURL
    return documentsDirectory.URLByAppendingPathComponent("download_test.bin")?.path ?: ""
}

actual fun getDummyUploadPath(context: Any): String {
    val fileManager = NSFileManager.defaultManager()
    val urls = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
    val documentsDirectory = urls.first() as NSURL
    return documentsDirectory.URLByAppendingPathComponent("upload_test.txt")?.path ?: ""
}

actual fun getDummyCompressionInputPath(context: Any): String {
    val fileManager = NSFileManager.defaultManager()
    val urls = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
    val documentsDirectory = urls.first() as NSURL
    return documentsDirectory.URLByAppendingPathComponent("compress_me")?.path ?: ""
}

actual fun getDummyCompressionOutputPath(context: Any): String {
    val fileManager = NSFileManager.defaultManager()
    val urls = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
    val documentsDirectory = urls.first() as NSURL
    return documentsDirectory.URLByAppendingPathComponent("compressed.zip")?.path ?: ""
}