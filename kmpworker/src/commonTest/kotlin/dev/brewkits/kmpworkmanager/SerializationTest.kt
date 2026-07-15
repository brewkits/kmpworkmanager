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

    @Test
    fun Constraints_maxRetries_should_round_trip() {
        // maxRetries is persisted inside chain definitions on iOS, so it must survive
        // serialization exactly.
        val original = Constraints(maxRetries = 7)

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<Constraints>(serialized)

        assertEquals(7, deserialized.maxRetries)
    }

    @Test
    fun Constraints_default_maxRetries_is_uncapped() {
        assertEquals(-1, Constraints().maxRetries)
    }

    @Test
    fun Constraints_from_pre_3_1_json_without_maxRetries_decodes_to_default() {
        // A chain definition persisted before maxRetries existed has no such key. It must
        // decode to the -1 default (uncapped / platform default), never fail.
        val legacyJson = """{"requiresNetwork":true,"isHeavyTask":true}"""

        val decoded = json.decodeFromString<Constraints>(legacyJson)

        assertEquals(-1, decoded.maxRetries, "Missing maxRetries key must default to -1 for back-compat.")
        assertEquals(true, decoded.requiresNetwork)
        assertEquals(true, decoded.isHeavyTask)
    }
}
