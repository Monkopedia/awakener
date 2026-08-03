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
     * This string becomes both the residue filename and the `SPANREED_AGENT_NAME`, and through
     * that the `agent_id` spanreed routes on — so two keys sharing a slug is two surfaces
     * sharing an agent, an inbox, and an accumulated model of Jason. The readable half cannot
     * carry any of that uniqueness — it is truncated, and every character that is not a letter
     * or digit is flattened to `-`. All of it rests on the trailing digest, which is taken over
     * the injective [canonical] and is wide enough that a program choosing its own `app_id` or
     * title cannot aim at another surface's slug.
     */
    val slug: String
        get() {
            val readable = canonical.map { c ->
                if (c.isLetterOrDigit() || c == '-') c.lowercaseChar() else '-'
            }.joinToString("").trim('-').replace(Regex("-+"), "-").take(SLUG_READABLE_MAX)
            return "$readable-${canonical.stableDigest()}"
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
 * SHA-256 over the string, truncated to 128 bits and rendered as 32 hex digits.
 *
 * The width is the point, and the input is why. Every input to [SurfaceKey.of] — `app_id` and
 * window title — is chosen by the program that owns the window, not by awakener and not by
 * Jason: `foot -a <anything>` sets one, and a title is whatever the program prints, including
 * remote text a terminal echoes. So a program that can open a window picks the string that is
 * hashed here, and the digest has to hold against a *chosen* input rather than against bad
 * luck. The 32-bit FNV-1a this replaced did not: a chosen-victim preimage took 1.5 seconds on
 * eight threads (#13), and since the slug becomes the `SPANREED_AGENT_NAME` and the residue
 * filename, that bought the attacker another surface's inbox and accumulated model.
 *
 * 128 bits leaves the birthday bound at 2^64 and a chosen preimage at 2^128, which is not a
 * budget a desktop application has.
 */
private fun String.stableDigest(): String =
    encodeToByteArray().sha256().copyOf(DIGEST_BYTES).toHex()

private const val DIGEST_BYTES = 16
