package dev.brewkits.kmpworkmanager.sample.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * ViewModel for the debug screen. Provides task state and lifecycle management.
 */
class DebugViewModel : KoinComponent {

    private val debugSource: DebugSource by inject()

    private val job = SupervisorJob()
    private val viewModelScope = CoroutineScope(Dispatchers.Main + job)

    private val _tasks = MutableStateFlow<List<DebugTaskInfo>>(emptyList())
    val tasks = _tasks.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _tasks.value = debugSource.getTasks()
        }
    }

    /**
     * Cancel all coroutines. Must be called when the ViewModel is no longer needed.
     */
    fun clear() {
        job.cancel()
    }
}
