package com.example.kmpworkmanagerv2.background.domain

fun createTaskTriggerOneTime(initialDelayMs: Long): TaskTrigger {
    return TaskTrigger.OneTime(initialDelayMs)
}

fun createConstraints(): Constraints {
    return Constraints()
}
