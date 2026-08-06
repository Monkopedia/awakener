package com.monkopedia.awakener.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Something a decoded value must satisfy beyond parsing as the right type.
 *
 * A codec settles what a value *is*; this settles what it may *be*. `-1` is a perfectly good
 * `Long` and a useless timeout, and until a flag can say so the config file's stated rule —
 * a bad value degrades to the default and is reported — covered only the half that fails to
 * decode. The other half changed behaviour silently, which against a hand-edited file on a
 * running desktop is the more expensive failure of the two.
 *
 * @param description how the constraint reads in `awakener-config list` and in the problem
 * reported for a value that breaks it. Phrased to follow the value: "must be at least 0".
 * @param nearest the closest usable value, for [InvalidValue.CLAMP]. Null where the
 * constraint cannot name one — an arbitrary predicate knows what it rejects and not what it
 * would have preferred — and such a flag then degrades to its default under every mode that
 * does not keep the typed value.
 */
class Requirement<T> internal constructor(
    val description: String,
    internal val accepts: (T) -> Boolean,
    internal val nearest: ((T) -> T)?,
)

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
    /** What a decoded value must satisfy, or null when only the codec constrains it. */
    internal val requires: Requirement<T>? = null,
) {
    /** The constraint in words, for help output. Empty when there is none. */
    val requirement: String get() = requires?.description.orEmpty()

    /**
     * Why [value] is unusable, phrased to follow it, or null when it is fine.
     *
     * Never throws, including when the predicate itself does: a snapshot is total, and a
     * requirement that blows up is a defect in the declaring module rather than a reason for
     * the process reading the config file to stop.
     */
    internal fun rejection(value: T): String? {
        val requires = requires ?: return null
        val accepted = runCatching { requires.accepts(value) }
            .getOrElse { return "could not be checked against '${requires.description}': $it" }
        return if (accepted) null else "must be ${requires.description}"
    }

    /** What to use in place of a [value] that [rejection] refused, under [mode]. */
    internal fun degraded(value: T, mode: InvalidValue): T = when (mode) {
        InvalidValue.DEGRADE -> default
        InvalidValue.CLAMP -> requires?.nearest?.let { runCatching { it(value) }.getOrNull() }
            ?: default
        InvalidValue.KEEP -> value
    }

    /**
     * Parses human input and refuses it if it breaks the requirement.
     *
     * Deliberately *not* the degrading path: a `set` is somebody typing a value at a prompt
     * that can answer them, so writing it and quietly using another is worse than saying no.
     * Degradation is for the file, which nothing is watching when it is read.
     */
    internal fun parseChecked(raw: String): JsonElement {
        val element = parseRaw(raw)
        val decoded = runCatching { decode(element) }.getOrNull() ?: return element
        rejection(decoded)?.let { throw IllegalArgumentException("$key $it, got '$raw'") }
        return element
    }

    override fun toString(): String = "Flag($key, default=$default)"
}

/**
 * A rule about a *snapshot* rather than about a value.
 *
 * Some constraints are relative — one flag's grace period has to outlast another's wait — and
 * those have no single key to degrade: either value is defensible alone, and it is the pair
 * that is wrong. So this only ever reports. [key] is where a reader should look first, so the
 * problem still names somewhere actionable.
 */
class Constraint internal constructor(
    val key: String,
    val description: String,
    internal val check: (Config) -> String?,
)

/**
 * The set of declared flags.
 *
 * Registration is global and eager: a flag exists from the moment its declaring object is
 * loaded, so anything that enumerates flags must first make sure the declaring objects are
 * loaded. Naming them one by one does not survive a module being added — the JVM entry points
 * use `FlagDiscovery` to find them by convention instead, and [requireLoaded] is left for the
 * bootstrap case (loading the flags that decide how discovery runs) and for tests.
 */
object Flags {
    private val registered = LinkedHashMap<String, Flag<*>>()
    private val constraints = mutableListOf<Constraint>()

    fun all(): List<Flag<*>> = registered.values.toList()

    fun byKey(key: String): Flag<*>? = registered[key]

    /** The snapshot-level rules, evaluated once per snapshot by [Config.of]. */
    fun allConstraints(): List<Constraint> = constraints.toList()

    /**
     * Declares a rule spanning more than one flag.
     *
     * Registered from a `*Flags` object like a flag, so classpath discovery loads it with the
     * flags it is about — a constraint that is only registered when somebody remembers to call
     * something is a constraint that is not checked on the snapshot that mattered.
     *
     * @param key the flag a reader should look at first. Not required to be unique: one flag can
     * be party to several rules, and the same rule names one key rather than all of them.
     * @param check the reason the snapshot is wrong, or null when it is fine.
     */
    fun constraint(key: String, description: String, check: (Config) -> String?): Constraint =
        Constraint(key, description, check).also { constraints += it }

    /**
     * A requirement from an arbitrary predicate.
     *
     * @param description phrased to follow the value — "must be ${description}" is what a reader
     * sees, so "at least 0" rather than "non-negative check".
     */
    fun <T> requires(
        description: String,
        nearest: ((T) -> T)? = null,
        accepts: (T) -> Boolean,
    ): Requirement<T> = Requirement(description, accepts, nearest)

    fun atLeast(min: Int): Requirement<Int> =
        Requirement("at least $min", { it >= min }, { maxOf(it, min) })

    fun atLeast(min: Long): Requirement<Long> =
        Requirement("at least $min", { it >= min }, { maxOf(it, min) })

    fun within(range: IntRange): Requirement<Int> = Requirement(
        "between ${range.first} and ${range.last}",
        { it in range },
        { it.coerceIn(range.first, range.last) },
    )

    fun within(range: LongRange): Requirement<Long> = Requirement(
        "between ${range.first} and ${range.last}",
        { it in range },
        { it.coerceIn(range.first, range.last) },
    )

    /** For a flag whose empty value means something else — a path, a command name. */
    fun nonBlank(): Requirement<String> = Requirement("not blank", { it.isNotBlank() }, null)

    /**
     * Forces [holders] to initialise so their flags are registered. Enumerating flags without
     * this — or without discovery having run — silently reports a subset, which would make
     * `config list` lie about what exists.
     *
     * **The call site is the mechanism; the body is a formality.** Naming an object as an
     * argument reads its `INSTANCE` field, and that is the active use that runs its class
     * initialiser — so every holder is already registered by the time control reaches the line
     * below, and would be even if this function did nothing at all. The loop exists to say
     * that out loud. Tidying it away is right about the loop and wrong about the function:
     * what a reader must not delete is a *call*, and `ConfigCli.bootstrap` plus four test
     * suites depend on theirs.
     *
     * `ConfigTest` holds the claim rather than leaving it a sentence: it asserts that a flag
     * key is unknown before the call that names its holder and known after it.
     */
    fun requireLoaded(vararg holders: Any) {
        holders.forEach { it.hashCode() }
    }

    private fun <T> register(flag: Flag<T>): Flag<T> {
        // Checked before the store is touched: discovery catches a failing class initialiser and
        // reports it, so a registration that put first would leave the rejected flag behind a
        // mere warning — and `list` would then print the loser's default under the winner's key.
        require(flag.key !in registered) {
            "duplicate flag key '${flag.key}' — keys are the config file's schema, so a " +
                "collision would make one of the two flags silently unsettable"
        }
        // A default that breaks its own requirement makes the degradation path land somewhere
        // the flag itself says is unusable, and leaves an unconfigured system wrong — which is
        // the one thing a default is for. Caught here because there is no run in which it is
        // right, so it is a defect in the declaration rather than in anybody's config file.
        flag.rejection(flag.default)?.let {
            throw IllegalArgumentException("flag '${flag.key}' default ${flag.default} $it")
        }
        registered[flag.key] = flag
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

    fun int(
        key: String,
        default: Int,
        description: String,
        requires: Requirement<Int>? = null,
    ): Flag<Int> = register(
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
            requires = requires,
        ),
    )

    fun long(
        key: String,
        default: Long,
        description: String,
        requires: Requirement<Long>? = null,
    ): Flag<Long> = register(
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
            requires = requires,
        ),
    )

    fun string(
        key: String,
        default: String,
        description: String,
        requires: Requirement<String>? = null,
    ): Flag<String> = register(
        Flag(
            key = key,
            default = default,
            description = description,
            choices = emptyList(),
            decode = { it.jsonPrimitive.content },
            encode = { JsonPrimitive(it) },
            parseRaw = { JsonPrimitive(it) },
            requires = requires,
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

    // A `clearForTest()` stood here, offered as the seam for declaring throwaway flags in
    // isolation. It was `internal` and unused, and a test seam with no test on it is
    // indistinguishable from dead code — but it was also the wrong seam: registration is
    // global and eager, so clearing it takes every other suite's flags and, since #69, every
    // declared Constraint with it, irrecoverably, for the rest of the JVM. Isolation needs a
    // registry a test can own rather than a global reset. Unique keys per suite is what the
    // tests here do instead, and it costs them nothing.
}
