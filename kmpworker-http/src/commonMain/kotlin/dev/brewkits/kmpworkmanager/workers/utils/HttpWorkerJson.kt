package dev.brewkits.kmpworkmanager.workers.utils

import kotlinx.serialization.json.Json

/**
 * Shared [Json] for the HTTP workers' config (de)serialization.
 *
 * Kept local to the `kmpworkmanager-http` artifact so these workers don't reach into the
 * core module's internal runtime — the core engine stays decoupled from this artifact.
 * Config mirrors the core runtime's Json (ignoreUnknownKeys / coerceInputValues /
 * encodeDefaults / explicitNulls=false) so on-the-wire JSON is identical.
 */
internal val HttpWorkerJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    explicitNulls = false
}
