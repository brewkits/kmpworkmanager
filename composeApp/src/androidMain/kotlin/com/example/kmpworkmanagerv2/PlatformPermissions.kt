package com.example.kmpworkmanagerv2

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver



// Actual cho việc quản lý quyền Exact Alarm
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
                } else {
                    hasPermission = true
                    shouldShowRequest = false
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
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")
                ).also { context.startActivity(it) }
            }
        }
    )
}