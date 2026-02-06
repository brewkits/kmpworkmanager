package dev.brewkits.kmpworkmanager.sample.utils

expect fun createDummyFiles(context: Any): Pair<String, String>
expect fun getDummyDownloadPath(context: Any): String
expect fun getDummyUploadPath(context: Any): String
expect fun getDummyCompressionInputPath(context: Any): String
expect fun getDummyCompressionOutputPath(context: Any): String
