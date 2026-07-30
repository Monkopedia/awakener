package com.monkopedia.awakener.config

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

/**
 * An immutable snapshot of flag values.
 *
 * A snapshot is deliberately total: [get] always returns a usable value. The config file is
 * expected to be hand-edited against a running daemon, so a typo in one flag must degrade to
 * that flag's default rather than take the process down or leave it half-configured. What
 * went wrong is reported through [problems] instead of thrown.
 */
class Config internal constructor(
    private val overrides: Map<String, JsonElement>,
    /** Keys present in the source that no flag declares, plus values that failed to decode. */
    val problems: List<Problem> = emptyList(),
) {
    data class Problem(val key: String, val reason: String)

    operator fun <T> get(flag: Flag<T>): T {
        val raw = overrides[flag.key] ?: return flag.default
        return runCatching { flag.decode(raw) }.getOrDefault(flag.default)
    }

    fun isOverridden(flag: Flag<*>): Boolean = flag.key in overrides

    /** Only the values that differ from defaults — what gets persisted. */
    fun overrides(): Map<String, JsonElement> = overrides.toMap()

    fun with(key: String, value: JsonElement): Config =
        Config(overrides + (key to value), problems)

    fun without(key: String): Config = Config(overrides - key, problems)

    companion object {
        val EMPTY: Config = Config(emptyMap())

        /**
         * Builds a snapshot from raw stored values, separating out anything unusable so it can
         * be surfaced rather than silently ignored. An unknown key is reported but retained:
         * it is usually a flag from a newer build or a typo the user will want told about, and
         * dropping it would silently discard their edit on the next write-back.
         */
        fun of(raw: Map<String, JsonElement>): Config {
            val problems = mutableListOf<Problem>()
            raw.forEach { (key, value) ->
                val flag = Flags.byKey(key)
                if (flag == null) {
                    problems += Problem(key, "no flag declares this key")
                } else {
                    runCatching { flag.decode(value) }.onFailure {
                        problems += Problem(key, "value $value is not valid: ${it.message}")
                    }
                }
            }
            return Config(raw, problems)
        }
    }
}

/**
 * A live, reloadable source of configuration.
 *
 * Implementations must publish a new snapshot when the underlying source changes, so callers
 * can react to a flag flip without restarting.
 */
interface ConfigStore {
    val config: StateFlow<Config>

    /** Applies a human-entered value. Throws if [key] is unknown or [raw] does not parse. */
    suspend fun set(key: String, raw: String)

    /** Removes an override, restoring the declared default. */
    suspend fun unset(key: String)
}
