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
 * v2.0.1+: Added proper coroutine scope cleanup to prevent memory leaks
 */
class DebugViewModel : KoinComponent {

    private val debugSource: DebugSource by inject()

    // v2.0.1+: Use SupervisorJob to allow proper cleanup
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
     * Cleanup method to cancel coroutines and prevent memory leaks.
     * v2.0.1+: MUST be called when ViewModel is no longer needed.
     */
    fun clear() {
        job.cancel()
    }
}
