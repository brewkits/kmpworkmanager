package io.kmp.taskmanager

import io.kmp.taskmanager.background.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SerializationTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun `TaskRequest with all fields should serialize and deserialize correctly`() {
        val original = TaskRequest(
            workerClassName = "TestWorker",
            inputJson = """{"key": "value"}""",
            constraints = Constraints(
                requiresNetwork = true,
                requiresCharging = true,
                isHeavyTask = true
            )
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<TaskRequest>(serialized)

        assertEquals(original, deserialized)
        assertEquals("TestWorker", deserialized.workerClassName)
        assertEquals("""{"key": "value"}""", deserialized.inputJson)
        assertNotNull(deserialized.constraints)
        assertTrue(deserialized.constraints!!.requiresNetwork)
        assertTrue(deserialized.constraints!!.requiresCharging)
        assertTrue(deserialized.constraints!!.isHeavyTask)
    }

    @Test
    fun `TaskRequest with null fields should serialize and deserialize correctly`() {
        val original = TaskRequest(
            workerClassName = "MinimalWorker",
            inputJson = null,
            constraints = null
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<TaskRequest>(serialized)

        assertEquals(original, deserialized)
        assertEquals("MinimalWorker", deserialized.workerClassName)
        assertEquals(null, deserialized.inputJson)
        assertEquals(null, deserialized.constraints)
    }

    @Test
    fun `Constraints with default values should serialize and deserialize correctly`() {
        val original = Constraints()

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<Constraints>(serialized)

        assertEquals(original, deserialized)
        assertEquals(false, deserialized.requiresNetwork)
        assertEquals(false, deserialized.requiresUnmeteredNetwork)
        assertEquals(false, deserialized.requiresCharging)
        assertEquals(false, deserialized.allowWhileIdle)
        assertEquals(Qos.Background, deserialized.qos)
        assertEquals(false, deserialized.isHeavyTask)
        assertEquals(BackoffPolicy.EXPONENTIAL, deserialized.backoffPolicy)
        assertEquals(30_000L, deserialized.backoffDelayMs)
    }

    @Test
    fun `Constraints with all fields set should serialize and deserialize correctly`() {
        val original = Constraints(
            requiresNetwork = true,
            requiresUnmeteredNetwork = true,
            requiresCharging = true,
            allowWhileIdle = true,
            qos = Qos.UserInitiated,
            isHeavyTask = true,
            backoffPolicy = BackoffPolicy.LINEAR,
            backoffDelayMs = 60_000L
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<Constraints>(serialized)

        assertEquals(original, deserialized)
        assertEquals(true, deserialized.requiresNetwork)
        assertEquals(true, deserialized.requiresUnmeteredNetwork)
        assertEquals(true, deserialized.requiresCharging)
        assertEquals(true, deserialized.allowWhileIdle)
        assertEquals(Qos.UserInitiated, deserialized.qos)
        assertEquals(true, deserialized.isHeavyTask)
        assertEquals(BackoffPolicy.LINEAR, deserialized.backoffPolicy)
        assertEquals(60_000L, deserialized.backoffDelayMs)
    }

    @Test
    fun `TaskRequest list should serialize and deserialize correctly`() {
        val original = listOf(
            TaskRequest("Worker1", """{"id": 1}"""),
            TaskRequest("Worker2", null, Constraints(requiresNetwork = true)),
            TaskRequest("Worker3")
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<List<TaskRequest>>(serialized)

        assertEquals(original, deserialized)
        assertEquals(3, deserialized.size)
        assertEquals("Worker1", deserialized[0].workerClassName)
        assertEquals("Worker2", deserialized[1].workerClassName)
        assertEquals("Worker3", deserialized[2].workerClassName)
    }

    @Test
    fun `Constraints with different QoS levels should serialize correctly`() {
        val qosLevels = listOf(
            Qos.Utility,
            Qos.Background,
            Qos.UserInitiated,
            Qos.UserInteractive
        )

        qosLevels.forEach { qos ->
            val constraints = Constraints(qos = qos)
            val serialized = json.encodeToString(constraints)
            val deserialized = json.decodeFromString<Constraints>(serialized)

            assertEquals(qos, deserialized.qos)
        }
    }

    @Test
    fun `Constraints with different BackoffPolicy should serialize correctly`() {
        val policies = listOf(
            BackoffPolicy.LINEAR,
            BackoffPolicy.EXPONENTIAL
        )

        policies.forEach { policy ->
            val constraints = Constraints(backoffPolicy = policy)
            val serialized = json.encodeToString(constraints)
            val deserialized = json.decodeFromString<Constraints>(serialized)

            assertEquals(policy, deserialized.backoffPolicy)
        }
    }

    @Test
    fun `TaskRequest with special characters in JSON should serialize correctly`() {
        val original = TaskRequest(
            workerClassName = "SpecialWorker",
            inputJson = """{"message": "Hello \"World\"!", "emoji": "🚀"}"""
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<TaskRequest>(serialized)

        assertEquals(original, deserialized)
        assertEquals("""{"message": "Hello \"World\"!", "emoji": "🚀"}""", deserialized.inputJson)
    }

    @Test
    fun `TaskRequest with empty workerClassName should serialize correctly`() {
        val original = TaskRequest(workerClassName = "")

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<TaskRequest>(serialized)

        assertEquals(original, deserialized)
        assertEquals("", deserialized.workerClassName)
    }

    @Test
    fun `Constraints with large backoffDelayMs should handle correctly`() {
        val original = Constraints(backoffDelayMs = Long.MAX_VALUE)

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<Constraints>(serialized)

        assertEquals(Long.MAX_VALUE, deserialized.backoffDelayMs)
    }
}
