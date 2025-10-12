package com.example.kmpworkmanagerv2.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DebugViewModel : KoinComponent {

    private val debugSource: DebugSource by inject()
    private val viewModelScope = CoroutineScope(Dispatchers.Main)

    private val _tasks = MutableStateFlow<List<DebugTaskInfo>>(emptyList())
    val tasks = _tasks.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _tasks.value = debugSource.getTasks()
        }
    }
}
