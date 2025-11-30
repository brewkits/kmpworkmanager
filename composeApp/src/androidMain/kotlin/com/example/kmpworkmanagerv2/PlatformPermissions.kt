package com.example.kmpworkmanagerv2

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.kmpworkmanagerv2.utils.Logger
import com.example.kmpworkmanagerv2.utils.LogTags

/**
 * Android implementation for Exact Alarm permission management
 */
@Composable
actual fun rememberExactAlarmPermissionState(): ExactAlarmPermissionState {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(false) }
    var shouldShowRequest by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    val permissionState = alarmManager?.canScheduleExactAlarms() ?: false
                    hasPermission = permissionState
                    shouldShowRequest = !permissionState

                    Logger.d(LogTags.PERMISSION, "Exact Alarm permission: $permissionState")
                } else {
                    hasPermission = true
                    shouldShowRequest = false
                    Logger.d(LogTags.PERMISSION, "Exact Alarm permission granted by default (API < 31)")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return ExactAlarmPermissionState(
        hasPermission = hasPermission,
        shouldShowRequest = shouldShowRequest,
        requestPermission = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Logger.i(LogTags.PERMISSION, "Requesting Exact Alarm permission")
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")
                ).also { context.startActivity(it) }
            }
        }
    )
}

/**
 * Android implementation for POST_NOTIFICATIONS permission (Android 13+)
 */
@Composable
actual fun rememberNotificationPermissionState(onPermissionResult: (Boolean) -> Unit): NotificationPermissionState {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(false) }
    var shouldShowRequest by remember { mutableStateOf(false) }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        shouldShowRequest = !isGranted

        // Callback to notify caller
        onPermissionResult(isGranted)

        if (isGranted) {
            Logger.i(LogTags.PERMISSION, "POST_NOTIFICATIONS permission granted")
        } else {
            Logger.w(LogTags.PERMISSION, "POST_NOTIFICATIONS permission denied")
        }
    }

    // Check permission status on lifecycle resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val permissionStatus = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                    hasPermission = permissionStatus == PackageManager.PERMISSION_GRANTED
                    shouldShowRequest = !hasPermission

                    Logger.d(LogTags.PERMISSION, "POST_NOTIFICATIONS permission: $hasPermission")
                } else {
                    // Permission not required on Android < 13
                    hasPermission = true
                    shouldShowRequest = false
                    Logger.d(LogTags.PERMISSION, "POST_NOTIFICATIONS not required (API < 33)")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return NotificationPermissionState(
        hasPermission = hasPermission,
        shouldShowRequest = shouldShowRequest,
        requestPermission = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Logger.i(LogTags.PERMISSION, "Requesting POST_NOTIFICATIONS permission")
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    )
}