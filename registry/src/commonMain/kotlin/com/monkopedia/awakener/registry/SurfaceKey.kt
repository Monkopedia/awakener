package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config

/**
 * The durable identity of a surface — what a binding is actually keyed on.
 *
 * This is deliberately *not* a compositor handle. sway's `con_id` is minted fresh every time a
 * window maps, so a binding keyed on it would be forgotten by the next login, which would
 * defeat the only reason this layer exists. A [SurfaceKey] is derived from facts that outlive
 * the window.
 *
 * The design brief settles that the binding unit is "whatever has a durable personal model
 * behind it" — the window for most apps, the *origin* for Chrome. Both shapes exist here from
 * the start so that adding `:chrome` later is a call site, not a schema migration.
 */
sealed interface SurfaceKey {
    /**
     * A lossless, stable string form. Used as the on-disk key and as the input to [slug], so it
     * must be injective: two different keys may never render the same string. Segments are
     * percent-escaped to guarantee that even for titles containing `:` or `%`.
     */
    val canonical: String

    /**
     * Most applications: the durable model belongs to the window.
     *
     * @param appId the compositor's `app_id`, which survives restarts where a window handle
     * does not.
     * @param discriminator distinguishes several windows of the same application, per
     * [RegistryFlags.windowIdentity]. Null means one agent for the whole application.
     */
    data class Window(val appId: String, val discriminator: String? = null) : SurfaceKey {
        override val canonical: String =
            "window:" + appId.escapeSegment() +
                (discriminator?.let { ":" + it.escapeSegment() } ?: "")
    }

    /**
     * Chrome, and anything else where what accumulates is "how I deal with this site" rather
     * than anything about a tab that will be closed in ten minutes.
     */
    data class Origin(val origin: String) : SurfaceKey {
        override val canonical: String = "origin:" + origin.escapeSegment()
    }

    /**
     * A short, readable, filesystem- and spanreed-safe name for this key.
     *
     * The readable part is truncated, so it is the trailing hash that carries uniqueness. That
     * matters more than it looks: this string becomes both the residue filename and the
     * `SPANREED_AGENT_NAME`, and a collision there would silently hand two surfaces the same
     * agent.
     */
    val slug: String
        get() {
            val readable = canonical.map { c ->
                if (c.isLetterOrDigit() || c == '-') c.lowercaseChar() else '-'
            }.joinToString("").trim('-').replace(Regex("-+"), "-").take(SLUG_READABLE_MAX)
            return "$readable-${canonical.stableHash()}"
        }

    companion object {
        private const val SLUG_READABLE_MAX = 48

        /**
         * Rebuilds a key from its [canonical] form. Returns null for anything this build does
         * not understand, which is how a bindings file written by a newer awakener degrades:
         * the unknown entry is reported and left alone rather than crashing the load.
         */
        fun parse(canonical: String): SurfaceKey? {
            val kind = canonical.substringBefore(':', missingDelimiterValue = "")
            val rest = canonical.substringAfter(':', missingDelimiterValue = "")
            if (kind.isEmpty()) return null
            val segments = rest.split(':').map { it.unescapeSegment() }
            return when (kind) {
                "window" -> when (segments.size) {
                    1 -> Window(segments[0])
                    2 -> Window(segments[0], segments[1])
                    else -> null
                }
                "origin" -> segments.singleOrNull()?.let(::Origin)
                else -> null
            }
        }

        /**
         * Derives the key for a surface, applying [RegistryFlags.windowIdentity].
         *
         * A surface that names an [SurfaceDescriptor.origin] is origin-keyed regardless of the
         * flag: the flag is about how windows of one app are split up, and an origin-multiplexed
         * surface has no meaningful window identity to split.
         */
        fun of(descriptor: SurfaceDescriptor, config: Config): SurfaceKey {
            descriptor.origin?.takeIf { it.isNotBlank() }?.let { return Origin(it) }
            // An xwayland window can report no app_id at all. Both ways out are lossy — the
            // title is separable but churns, the fixed key is stable but collapses every such
            // window onto one agent — so it is [RegistryFlags.missingAppId]'s call. Either way
            // the window gets a key, rather than being dropped on the floor.
            val appId = descriptor.appId?.takeIf { it.isNotBlank() }
                ?: when (config[RegistryFlags.missingAppId]) {
                    MissingAppId.TITLE -> descriptor.title?.takeIf { it.isNotBlank() }
                    MissingAppId.UNIDENTIFIED -> null
                }
                ?: UNIDENTIFIED
            return when (config[RegistryFlags.windowIdentity]) {
                WindowIdentity.APP_ID -> Window(appId)
                WindowIdentity.APP_ID_AND_TITLE -> Window(appId, descriptor.title.orEmpty())
                WindowIdentity.APP_ID_AND_PID -> Window(appId, descriptor.pid?.toString() ?: "")
            }
        }

        const val UNIDENTIFIED = "unidentified"
    }
}

/**
 * The compositor-agnostic facts a [SurfaceKey] can be derived from.
 *
 * This exists so `:registry` never has to know what a `con_id` is — the window-management layer
 * translates its own surfaces into this shape, which keeps the "nothing above `:wm` learns
 * which compositor it is talking to" agreement intact in the direction that matters here.
 */
data class SurfaceDescriptor(
    val appId: String?,
    val title: String?,
    val pid: Int?,
    /** Set only by a surface manager that multiplexes origins behind one window (Chrome). */
    val origin: String? = null,
)

private fun String.escapeSegment(): String = buildString(length) {
    this@escapeSegment.forEach { c ->
        when (c) {
            '%' -> append("%25")
            ':' -> append("%3A")
            else -> append(c)
        }
    }
}

private fun String.unescapeSegment(): String = replace("%3A", ":").replace("%25", "%")

/**
 * FNV-1a, rendered as eight hex digits.
 *
 * Hand-rolled because `commonMain` has no hashing and pulling a crypto dependency in to
 * disambiguate filenames would be absurd. It is not used for anything where an adversary picks
 * the input — collision resistance here is about two of Jason's windows, not an attacker.
 */
private fun String.stableHash(): String {
    var hash = 2166136261u
    encodeToByteArray().forEach { byte ->
        hash = hash xor (byte.toUInt() and 0xffu)
        hash *= 16777619u
    }
    return hash.toString(16).padStart(8, '0')
}
