package dev.brewkits.kmpworkmanager.sample.workers

import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory

/**
 * Sample WorkerFactory for Android - v2.1.0 Demo App.
 *
 * This factory demonstrates how to:
 * - Implement AndroidWorkerFactory for v2.1.0
 * - Register workers by class name
 * - Use common worker implementations
 */
class SampleWorkerFactory : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        return when (workerClassName) {
            "DemoWorker" -> DemoWorkerAndroid()
            "HeavyWorker" -> HeavyWorkerAndroid()
            else -> {
                println("❌ Unknown worker: $workerClassName")
                null
            }
        }
    }
}

/**
 * Android wrapper for DemoWorker.
 */
private class DemoWorkerAndroid : AndroidWorker {
    private val worker = DemoWorker()

    override suspend fun doWork(input: String?): Boolean {
        return worker.doWork(input)
    }
}

/**
 * Android wrapper for HeavyWorker.
 */
private class HeavyWorkerAndroid : AndroidWorker {
    private val worker = HeavyWorker()

    override suspend fun doWork(input: String?): Boolean {
        return worker.doWork(input)
    }
}
