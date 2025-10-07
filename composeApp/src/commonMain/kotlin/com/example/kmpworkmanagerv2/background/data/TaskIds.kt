package com.example.kmpworkmanagerv2.background.data

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

@OptIn(ExperimentalObjCName::class)
@ObjCName("TaskIds")
object TaskIds {
    const val HEAVY_TASK_1 = "heavy-task-1"
    const val ONE_TIME_UPLOAD = "one-time-upload"
    const val PERIODIC_SYNC_TASK = "periodic-sync-task"
    const val EXACT_REMINDER = "exact-reminder"
}
