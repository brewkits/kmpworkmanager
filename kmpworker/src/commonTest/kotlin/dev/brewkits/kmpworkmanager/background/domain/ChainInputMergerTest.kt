package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for [ChainInputMerger] — the shared engine behind
 * [TaskRequest.mergeOutputFromPreviousStep].
 *
 * These live in `commonTest` on purpose. The merge runs inside two completely different
 * chain engines (Android's `BaseKmpWorker` reading WorkManager `Data`, iOS's `ChainExecutor`
 * passing a `JsonObject` in memory), so the only way "both platforms merge identically"
 * stays true is if both call one implementation that is pinned here.
 */
class ChainInputMergerTest {

    private fun parse(raw: String): JsonObject =
        Json.parseToJsonElement(raw) as JsonObject

    @Test
    fun previousOutputWins_onKeyCollision() {
        val base = """{"url":"https://host","retries":3}"""
        val previous = buildJsonObject {
            put("filePath", "/tmp/x.zip")
            put("retries", 1)
        }

        val merged = parse(ChainInputMerger.merge(base, previous))

        assertEquals("https://host", merged["url"]?.jsonPrimitive?.content, "Untouched base keys must survive.")
        assertEquals("/tmp/x.zip", merged["filePath"]?.jsonPrimitive?.content, "New keys from the previous step must appear.")
        assertEquals(
            "1", merged["retries"]?.jsonPrimitive?.content,
            "On collision the PREVIOUS step's value must win — this is the documented " +
                "OverwritingInputMerger semantic that Android relies on natively."
        )
    }

    @Test
    fun nullBaseInput_yieldsPreviousOutputAlone() {
        val previous = buildJsonObject { put("token", "abc") }
        val merged = parse(ChainInputMerger.merge(null, previous))
        assertEquals(1, merged.size)
        assertEquals("abc", merged["token"]?.jsonPrimitive?.content)
    }

    @Test
    fun blankBaseInput_yieldsPreviousOutputAlone() {
        val previous = buildJsonObject { put("token", "abc") }
        assertEquals(
            parse(ChainInputMerger.merge(null, previous)),
            parse(ChainInputMerger.merge("   ", previous)),
            "A blank input string must behave exactly like a null one."
        )
    }

    @Test
    fun malformedBaseInput_doesNotThrow_andStillDeliversPreviousOutput() {
        // A worker that wrote a bad input string must not be able to kill the chain:
        // losing the malformed input is recoverable, losing the step is not.
        val previous = buildJsonObject { put("token", "abc") }
        val merged = parse(ChainInputMerger.merge("{not valid json", previous))
        assertEquals("abc", merged["token"]?.jsonPrimitive?.content)
    }

    @Test
    fun nonObjectBaseInput_isTreatedAsEmpty() {
        // Valid JSON, but not an object — there is nothing to merge INTO.
        val previous = buildJsonObject { put("k", "v") }
        for (raw in listOf("[1,2,3]", "\"a string\"", "42", "true", "null")) {
            val merged = parse(ChainInputMerger.merge(raw, previous))
            assertEquals(1, merged.size, "Base '$raw' should contribute no keys.")
            assertEquals("v", merged["k"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun emptyPreviousOutput_preservesBaseInputExactly() {
        val base = """{"url":"https://host","retries":3}"""
        val merged = parse(ChainInputMerger.merge(base, JsonObject(emptyMap())))
        assertEquals(parse(base), merged, "An empty previous output must not disturb the base input.")
    }

    @Test
    fun nestedObjectsAreReplacedWholesale_notDeepMerged() {
        // Documents the deliberate choice: this is a shallow merge, matching
        // OverwritingInputMerger. A deep merge would silently blend two workers'
        // nested structures in ways neither author intended.
        val base = """{"config":{"a":1,"b":2}}"""
        val previous = buildJsonObject {
            put("config", buildJsonObject { put("a", 99) })
        }
        val merged = parse(ChainInputMerger.merge(base, previous))
        val config = merged["config"] as JsonObject
        assertEquals(1, config.size, "Nested object must be replaced, not deep-merged.")
        assertEquals("99", config["a"]?.jsonPrimitive?.content)
    }

    @Test
    fun mergedResultIsValidJsonObject_reParsable() {
        // The result is fed straight back in as a task's inputJson, so it must round-trip.
        val merged = ChainInputMerger.merge("""{"a":1}""", buildJsonObject { put("b", "x") })
        val reparsed = parse(merged)
        assertEquals(2, reparsed.size)
        assertTrue(reparsed["a"]?.jsonPrimitive is JsonPrimitive)
    }

    @Test
    fun parseObjectOrEmpty_handlesEveryDegenerateInput() {
        assertEquals(emptyMap(), ChainInputMerger.parseObjectOrEmpty(null))
        assertEquals(emptyMap(), ChainInputMerger.parseObjectOrEmpty(""))
        assertEquals(emptyMap(), ChainInputMerger.parseObjectOrEmpty("   "))
        assertEquals(emptyMap(), ChainInputMerger.parseObjectOrEmpty("{broken"))
        assertEquals(emptyMap(), ChainInputMerger.parseObjectOrEmpty("[1,2]"))
        assertEquals(1, ChainInputMerger.parseObjectOrEmpty("""{"a":1}""").size)
    }
}
