package dev.brewkits.kmpworkmanager.sample.workers

import dev.brewkits.kmpworkmanager.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory

/**
 * Sample WorkerFactory for iOS - v2.1.0 Demo App.
 *
 * This factory demonstrates how to:
 * - Implement IosWorkerFactory for v2.1.0
 * - Register workers by class name
 * - Use common worker implementations
 */
class SampleWorkerFactory : IosWorkerFactory {
    override fun createWorker(workerClassName: String): IosWorker? {
        return when (workerClassName) {
            "DemoWorker" -> DemoWorkerIos()
            "HeavyWorker" -> HeavyWorkerIos()
            else -> {
                println("❌ Unknown worker: $workerClassName")
                null
            }
        }
    }
}

/**
 * iOS wrapper for DemoWorker.
 */
private class DemoWorkerIos : IosWorker {
    private val worker = DemoWorker()

    override suspend fun doWork(input: String?): Boolean {
        return worker.doWork(input)
    }
}

/**
 * iOS wrapper for HeavyWorker.
 */
private class HeavyWorkerIos : IosWorker {
    private val worker = HeavyWorker()

    override suspend fun doWork(input: String?): Boolean {
        return worker.doWork(input)
    }
}
