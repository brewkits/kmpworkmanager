package io.kmp.taskmanager.sample.utils

import android.util.Log

/**
 * Android implementation of Logger using Android's Log system
 */
internal actual object LoggerPlatform {
    private const val TAG = "KMP_TaskManager"

    actual fun log(level: Logger.Level, message: String) {
        when (level) {
            Logger.Level.DEBUG -> Log.d(TAG, message)
            Logger.Level.INFO -> Log.i(TAG, message)
            Logger.Level.WARN -> Log.w(TAG, message)
            Logger.Level.ERROR -> Log.e(TAG, message)
        }
    }
}
