package com.monkopedia.awakener.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A single runtime-tunable knob.
 *
 * Every behavioural choice in awakener is expected to be a flag rather than a constant, so
 * that behaviour can be changed against a running daemon without a rebuild. Flags are
 * declared once, statically, and carry their own default, documentation, and codec — which
 * is what lets `config list` be self-documenting instead of a hand-maintained list that
 * drifts from the code.
 */
class Flag<T> internal constructor(
    val key: String,
    val default: T,
    val description: String,
    /** Values that are meaningful for this flag, for help output. Empty when unbounded. */
    val choices: List<String>,
    internal val decode: (JsonElement) -> T,
    internal val encode: (T) -> JsonElement,
    /** Parses human input (CLI argument, environment variable) into stored form. */
    internal val parseRaw: (String) -> JsonElement,
) {
    override fun toString(): String = "Flag($key, default=$default)"
}

/**
 * The set of declared flags.
 *
 * Registration is global and eager: a flag exists from the moment its declaring object is
 * loaded, so anything that enumerates flags must first touch the objects that declare them.
 * [requireLoaded] exists for that.
 */
object Flags {
    private val registered = LinkedHashMap<String, Flag<*>>()

    fun all(): List<Flag<*>> = registered.values.toList()

    fun byKey(key: String): Flag<*>? = registered[key]

    /**
     * Forces [holders] to initialise so their flags are registered. Enumerating flags without
     * this silently reports a subset, which would make `config list` lie about what exists.
     */
    fun requireLoaded(vararg holders: Any) {
        holders.forEach { it.hashCode() }
    }

    private fun <T> register(flag: Flag<T>): Flag<T> {
        val clash = registered.put(flag.key, flag)
        require(clash == null) {
            "duplicate flag key '${flag.key}' — keys are the config file's schema, so a " +
                "collision would make one of the two flags silently unsettable"
        }
        return flag
    }

    fun boolean(key: String, default: Boolean, description: String): Flag<Boolean> = register(
        Flag(
            key = key,
            default = default,
            description = description,
            choices = listOf("true", "false"),
            decode = { it.jsonPrimitive.booleanOrNull ?: error("not a boolean: $it") },
            encode = { JsonPrimitive(it) },
            parseRaw = {
                JsonPrimitive(
                    it.toBooleanStrictOrNull()
                        ?: throw IllegalArgumentException("expected true or false, got '$it'"),
                )
            },
        ),
    )

    fun int(key: String, default: Int, description: String): Flag<Int> = register(
        Flag(
            key = key,
            default = default,
            description = description,
            choices = emptyList(),
            decode = { it.jsonPrimitive.intOrNull ?: error("not an int: $it") },
            encode = { JsonPrimitive(it) },
            parseRaw = {
                JsonPrimitive(
                    it.toIntOrNull()
                        ?: throw IllegalArgumentException("expected an integer, got '$it'"),
                )
            },
        ),
    )

    fun long(key: String, default: Long, description: String): Flag<Long> = register(
        Flag(
            key = key,
            default = default,
            description = description,
            choices = emptyList(),
            decode = { it.jsonPrimitive.longOrNull ?: error("not a long: $it") },
            encode = { JsonPrimitive(it) },
            parseRaw = {
                JsonPrimitive(
                    it.toLongOrNull()
                        ?: throw IllegalArgumentException("expected an integer, got '$it'"),
                )
            },
        ),
    )

    fun string(key: String, default: String, description: String): Flag<String> = register(
        Flag(
            key = key,
            default = default,
            description = description,
            choices = emptyList(),
            decode = { it.jsonPrimitive.content },
            encode = { JsonPrimitive(it) },
            parseRaw = { JsonPrimitive(it) },
        ),
    )

    inline fun <reified E : Enum<E>> enum(
        key: String,
        default: E,
        description: String,
    ): Flag<E> = enumOf(key, default, description, enumValues<E>().toList())

    @PublishedApi
    internal fun <E : Enum<E>> enumOf(
        key: String,
        default: E,
        description: String,
        values: List<E>,
    ): Flag<E> {
        fun lookup(name: String): E = values.firstOrNull { it.name.equals(name, true) }
            ?: throw IllegalArgumentException(
                "expected one of ${values.joinToString("|") { it.name }}, got '$name'",
            )
        return register(
            Flag(
                key = key,
                default = default,
                description = description,
                choices = values.map { it.name },
                decode = { lookup(it.jsonPrimitive.content) },
                encode = { JsonPrimitive(it.name) },
                parseRaw = { JsonPrimitive(lookup(it).name) },
            ),
        )
    }

    /** Test seam: drops registrations so suites can declare throwaway flags in isolation. */
    internal fun clearForTest() = registered.clear()
}
