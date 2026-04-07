package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun TaskRequest_with_all_fields_should_serialize_and_deserialize_correctly() {
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

        assertEquals(original.workerClassName, deserialized.workerClassName)
        assertEquals(original.inputJson, deserialized.inputJson)
        assertNotNull(deserialized.constraints)
        assertEquals(original.constraints!!.requiresNetwork, deserialized.constraints!!.requiresNetwork)
        assertEquals(original.constraints!!.requiresCharging, deserialized.constraints!!.requiresCharging)
        assertEquals(original.constraints!!.isHeavyTask, deserialized.constraints!!.isHeavyTask)
    }

    @Test
    fun TaskRequest_with_null_fields_should_serialize_and_deserialize_correctly() {
        val original = TaskRequest(
            workerClassName = "MinimalWorker",
            inputJson = null,
            constraints = null
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<TaskRequest>(serialized)

        assertEquals(original.workerClassName, deserialized.workerClassName)
        assertEquals(original.inputJson, deserialized.inputJson)
        assertEquals(original.constraints, deserialized.constraints)
    }

    @Test
    fun TaskRequest_list_should_serialize_and_deserialize_correctly() {
        val original = listOf(
            TaskRequest("Worker1", """{"id": 1}"""),
            TaskRequest("Worker2", null, Constraints(requiresNetwork = true)),
            TaskRequest("Worker3")
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<List<TaskRequest>>(serialized)

        assertEquals(original.size, deserialized.size)
        assertEquals(original[0].workerClassName, deserialized[0].workerClassName)
    }

    @Test
    fun TaskRequest_with_special_characters_in_JSON_should_serialize_correctly() {
        val original = TaskRequest(
            workerClassName = "SpecialWorker",
            inputJson = """{"message": "Hello \"World\"!", "emoji": "🚀"}"""
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<TaskRequest>(serialized)

        assertEquals(original.inputJson, deserialized.inputJson)
    }

    @Test
    fun TaskRequest_with_empty_workerClassName_should_serialize_correctly() {
        val original = TaskRequest(workerClassName = "")

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<TaskRequest>(serialized)

        assertEquals(original.workerClassName, deserialized.workerClassName)
    }
}
