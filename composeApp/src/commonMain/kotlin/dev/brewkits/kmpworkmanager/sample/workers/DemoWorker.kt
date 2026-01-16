package dev.brewkits.kmpworkmanager.sample.workers

import dev.brewkits.kmpworkmanager.background.domain.Worker
import kotlinx.coroutines.delay

/**
 * Demo worker for v2.1.0 sample app.
 *
 * This worker demonstrates:
 * - Cross-platform worker implementation
 * - Simple async operation (delay simulation)
 * - Success/failure handling
 */
class DemoWorker : Worker {
    override suspend fun doWork(input: String?): Boolean {
        println("✅ DemoWorker started with input: $input")

        // Simulate work
        delay(2000)

        println("✅ DemoWorker completed successfully")
        return true
    }
}

/**
 * Heavy processing worker for long-running tasks.
 */
class HeavyWorker : Worker {
    override suspend fun doWork(input: String?): Boolean {
        println("⚙️ HeavyWorker started (long-running task)")

        // Simulate heavy processing
        delay(5000)

        println("✅ HeavyWorker completed")
        return true
    }
}
