package dev.brewkits.kmpworkmanager.sample.background.workers

import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.sample.background.data.IosWorker
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskEventBus
import kotlinx.coroutines.delay
import kotlin.math.sqrt
import kotlin.time.measureTime

class HeavyProcessingWorker : IosWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        println(" KMP_BG_TASK_iOS: Starting HeavyProcessingWorker...")
        println(" KMP_BG_TASK_iOS: Input: $input")

        return try {
            println(" KMP_BG_TASK_iOS: 🔥 Starting heavy computation...")

            // Real heavy computation: Calculate prime numbers
            var primes: List<Int> = emptyList()
            val duration = measureTime {
                primes = calculatePrimes(10000)
            }

            println(" KMP_BG_TASK_iOS: ✓ Calculated ${primes.size} prime numbers")
            println(" KMP_BG_TASK_iOS: ⚡ Computation took ${duration.inWholeMilliseconds}ms")
            println(" KMP_BG_TASK_iOS: 📊 First 10 primes: ${primes.take(10)}")
            println(" KMP_BG_TASK_iOS: 📊 Last 10 primes: ${primes.takeLast(10)}")

            // Simulate some processing time
            println(" KMP_BG_TASK_iOS: 💾 Saving results...")
            delay(2000)

            println(" KMP_BG_TASK_iOS: 🎉 HeavyProcessingWorker finished successfully.")

            // Emit completion event
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Heavy Processing",
                    success = true,
                    message = "✅ Calculated ${primes.size} primes in ${duration.inWholeMilliseconds}ms"
                )
            )

            return WorkerResult.Success(
                message = "Calculated ${primes.size} primes in ${duration.inWholeMilliseconds}ms",
                data = buildJsonObject {
                    put("primeCount", primes.size)
                    put("durationMs", duration.inWholeMilliseconds)
                    put("firstPrimes", primes.take(10).toString())
                    put("lastPrimes", primes.takeLast(10).toString())
                }
            )
        } catch (e: Exception) {
            println(" KMP_BG_TASK_iOS: HeavyProcessingWorker failed: ${e.message}")
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Heavy Processing",
                    success = false,
                    message = "❌ Task failed: ${e.message}"
                )
            )
            WorkerResult.Failure("Heavy processing failed: ${e.message}")
        }
    }

    private fun calculatePrimes(limit: Int): List<Int> {
        val primes = mutableListOf<Int>()
        for (num in 2..limit) {
            if (isPrime(num)) {
                primes.add(num)
            }
        }
        return primes
    }

    private fun isPrime(n: Int): Boolean {
        if (n < 2) return false
        if (n == 2) return true
        if (n % 2 == 0) return false

        val sqrtN = sqrt(n.toDouble()).toInt()
        for (i in 3..sqrtN step 2) {
            if (n % i == 0) return false
        }
        return true
    }
}
