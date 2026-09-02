package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Shared implementation of the chain InputMerger used by [TaskRequest.mergeOutputFromPreviousStep].
 *
 * Lives in `commonMain` deliberately: Android and iOS execute chains through completely
 * different engines (WorkManager's `WorkContinuation` vs. `ChainExecutor`), but the *merge
 * semantics* a user observes must be identical on both. Keeping one implementation here is
 * what makes that guarantee testable in `commonTest` rather than asserted twice.
 */
internal object ChainInputMerger {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Merges [previousOutput] into [baseInputJson].
     *
     * Semantics: **overwriting merge** — fields from [previousOutput] win over same-key
     * fields in [baseInputJson]. This mirrors `OverwritingInputMerger`, WorkManager's
     * default merger for chained work, so Android's native behaviour and iOS's emulated
     * behaviour agree.
     *
     * ```
     * base:     { "url": "https://host", "retries": 3 }
     * previous: { "filePath": "/tmp/x.zip", "retries": 1 }
     * result:   { "url": "https://host", "retries": 1, "filePath": "/tmp/x.zip" }
     * ```
     *
     * A malformed or non-object [baseInputJson] is treated as empty rather than throwing:
     * a chain must not die because an upstream worker wrote a bad input string. The
     * previous step's output is still delivered, which is the more useful failure mode.
     *
     * @param baseInputJson The task's own `inputJson` (may be null, blank, or malformed).
     * @param previousOutput The preceding step's `WorkerResult.Success.data`.
     * @return A JSON object string safe to pass as the task's `inputJson`.
     */
    fun merge(baseInputJson: String?, previousOutput: JsonObject): String {
        val baseElements: Map<String, JsonElement> = parseObjectOrEmpty(baseInputJson)
        val merged = buildMap<String, JsonElement> {
            putAll(baseElements)
            putAll(previousOutput) // previous-step keys win on collision
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(merged))
    }

    /**
     * Parses [raw] into a [JsonObject]'s entries, or returns an empty map when [raw] is
     * null/blank, not valid JSON, or valid JSON that is not an object (e.g. `[1,2]`, `"x"`).
     */
    fun parseObjectOrEmpty(raw: String?): Map<String, JsonElement> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            (json.parseToJsonElement(raw) as? JsonObject)?.toMap() ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
