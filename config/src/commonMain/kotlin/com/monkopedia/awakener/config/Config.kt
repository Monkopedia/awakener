package com.monkopedia.awakener.config

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

/**
 * An immutable snapshot of flag values.
 *
 * A snapshot is deliberately total: [get] always returns a usable value. The config file is
 * expected to be hand-edited against a live desktop, so a typo in one flag must degrade to
 * that flag's default rather than take the process down or leave it half-configured. What
 * went wrong is reported through [problems] instead of thrown.
 *
 * "Usable" means the flag's own [Requirement] as well as its codec. A value that decodes and is
 * out of range used to pass through untouched with nothing said, which is the quieter half of
 * the same failure: the process kept running and behaved differently from what was typed.
 */
class Config internal constructor(
    private val overrides: Map<String, JsonElement>,
    /**
     * Everything wrong with the source: keys no flag declares, values that failed to decode,
     * values that decoded and broke their flag's requirement, and snapshot-level [Constraint]s
     * that do not hold.
     */
    val problems: List<Problem> = emptyList(),
) {
    data class Problem(val key: String, val reason: String)

    operator fun <T> get(flag: Flag<T>): T {
        val raw = overrides[flag.key] ?: return flag.default
        val decoded = runCatching { flag.decode(raw) }.getOrElse { return flag.default }
        // The early return for an unconstrained flag is what keeps this from recursing:
        // ConfigFlags.invalidValue declares no requirement, so reading it never gets this far.
        // A flag that does declare one must therefore never be the mode flag itself.
        if (flag.rejection(decoded) == null) return decoded
        return flag.degraded(decoded, this[ConfigFlags.invalidValue])
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
         *
         * Three things can be wrong with a stored value and all three are reported the same way,
         * because to the person holding the editor they are one mistake: a key nothing declares,
         * a value that will not decode, and a value that decodes and is out of range. The last
         * one is the quiet one — it parses, so nothing objected, and behaviour simply differed
         * from what was typed. On top of those, a snapshot can be wrong as a whole while every
         * value in it is fine; see [Constraint].
         *
         * @param found problems the caller established before the values reached here — an
         * override it had to discard to build [raw] at all. They are carried rather than
         * re-derived because nothing downstream can see what was dropped.
         * @param origin where a key's value came from, when it is somewhere other than the
         * caller's own file — an `AWAKENER_*` variable, say. A problem reported without it
         * names a key and sends the reader to grep a file that does not contain the value.
         */
        fun of(
            raw: Map<String, JsonElement>,
            found: List<Problem> = emptyList(),
            origin: (String) -> String? = { null },
        ): Config {
            // Built first and without problems so that the reports below can say what [get]
            // will actually answer — which depends on config.validation.invalid_value, and so
            // is not knowable from the flag and the stored value alone.
            val staged = Config(raw)
            val problems = found.toMutableList()
            raw.forEach { (key, value) ->
                val flag = Flags.byKey(key)
                val problem = if (flag == null) {
                    Problem(key, "no flag declares this key")
                } else {
                    flag.problemWith(value, staged)
                }
                problem?.let { problems += it.setBy(origin(key)) }
            }
            Flags.allConstraints().forEach { constraint ->
                // A constraint that throws is a defect in the module that declared it, and a
                // snapshot that refused to build over one would take the process down for a
                // rule whose entire job is to report. So it reports itself.
                runCatching { constraint.check(staged) }
                    .getOrElse { "'${constraint.description}' could not be checked: $it" }
                    ?.let { problems += Problem(constraint.key, it) }
            }
            return Config(raw, problems)
        }

        private fun <T> Flag<T>.problemWith(value: JsonElement, staged: Config): Problem? {
            val decoded = runCatching { decode(value) }.getOrElse {
                return Problem(key, "value $value is not valid: ${it.message}; using $default")
            }
            val rejection = rejection(decoded) ?: return null
            return Problem(key, "value $value $rejection; using ${staged[this]}")
        }

        /** Names where the value came from, when it is not where the reader would look. */
        private fun Problem.setBy(origin: String?): Problem =
            if (origin == null) this else copy(reason = "$reason (set by $origin)")
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

    /**
     * Applies a human-entered value. Throws if [key] is unknown, if [raw] does not parse, or if
     * it breaks the flag's requirement — a value typed at something that can answer is refused
     * rather than degraded, which is the opposite of what the file gets and for the opposite
     * reason: there is somebody there to be told.
     */
    suspend fun set(key: String, raw: String)

    /** Removes an override, restoring the declared default. */
    suspend fun unset(key: String)
}
