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
     *
     * Injective **over the whole key, kind included**. Escaping alone only stops two keys of
     * one kind from colliding; the leading kind is what stops a key derived from a title from
     * rendering what a key derived from an `app_id` renders, which is a collision no amount of
     * escaping inside the segments could have prevented (#95).
     */
    val canonical: String

    /**
     * Most applications: the durable model belongs to the window, identified by its `app_id`.
     *
     * @param appId the compositor's `app_id`, which survives restarts where a window handle
     * does not.
     * @param discriminator distinguishes several windows of the same application, per
     * [RegistryFlags.windowIdentity]. Null means one agent for the whole application.
     */
    data class Window(val appId: String, val discriminator: String? = null) : SurfaceKey {
        override val canonical: String = render("window", appId, discriminator)
    }

    /**
     * A window that reported no `app_id`, keyed on its title under [MissingAppId.TITLE].
     *
     * **A separate kind, not a [Window] holding a title, and that is the whole of #95.** These
     * two strings come from different fields but the same source — the program that owns the
     * window picks both — so folding a title into the `app_id` slot let one window reach
     * another's identity by *copying* rather than by colliding: `xterm -T firefox` rendered
     * `window:firefox`, byte-for-byte what a real Firefox rendered, and from there the same
     * slug, the same residue file, the same `SPANREED_AGENT_NAME` and the same `agent_id`.
     *
     * No width of digest could have stopped it. [stableDigest] defends against a program
     * *searching* for an input that hashes to another surface's slug, and there was no search
     * to do: the two [canonical]s were equal before hashing, so the cost was zero rather than
     * the 2^128 that value is chosen for. Only the namespace separates them, so it has to be
     * in the string the digest is taken over rather than beside it.
     *
     * @param discriminator as [Window.discriminator]. Under
     * [WindowIdentity.APP_ID_AND_TITLE] it repeats [title], which is redundant rather than
     * wrong — the flag's meaning is "split by title", and it splits nothing here because the
     * key is already the title.
     */
    data class Titled(val title: String, val discriminator: String? = null) : SurfaceKey {
        override val canonical: String = render("title", title, discriminator)
    }

    /**
     * A window with neither an `app_id` nor a usable title, or one under
     * [MissingAppId.UNIDENTIFIED]. Every such window on the desktop shares this key.
     *
     * Also a separate kind, for the same reason and by the cheaper route: while this was
     * `Window("unidentified")`, any Wayland client could join the pool by declaring that
     * `app_id` — `foot -a unidentified` — with no xwayland and no flag flip involved. There
     * is now no string an `app_id` can take that renders this, because the kind carries no
     * caller-supplied segment at all.
     */
    data class Unidentified(val discriminator: String? = null) : SurfaceKey {
        override val canonical: String =
            "unidentified" + (discriminator?.let { ":" + it.escapeSegment() } ?: "")
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
     *
     * "Rests on the digest" is only true once [canonical] is injective over *provenance* as
     * well as over content — see [Titled]. A width defends against a search; it never
     * defended against two fields rendering the same bytes, and until #95 two of them did.
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
         *
         * The kind is read as everything before the *first* colon, or the whole string when
         * there is none — [Unidentified] carries no segment of its own, so `unidentified` with
         * no colon has to name a kind rather than fall out as a malformed key.
         */
        fun parse(canonical: String): SurfaceKey? {
            val split = canonical.indexOf(':')
            val kind = if (split < 0) canonical else canonical.substring(0, split)
            if (kind.isEmpty()) return null
            val segments = if (split < 0) {
                emptyList()
            } else {
                canonical.substring(split + 1).split(':').map { it.unescapeSegment() }
            }
            return when (kind) {
                // Explicit lambdas rather than `::Window`: a constructor with a defaulted
                // second parameter can satisfy two function types, and which one a callable
                // reference picks is not worth making a reader work out.
                "window" -> segments.asWindowLike { head, discriminator ->
                    Window(head, discriminator)
                }
                "title" -> segments.asWindowLike { head, discriminator ->
                    Titled(head, discriminator)
                }
                "unidentified" -> when (segments.size) {
                    0 -> Unidentified()
                    1 -> Unidentified(segments[0])
                    else -> null
                }
                "origin" -> segments.singleOrNull()?.let(::Origin)
                else -> null
            }
        }

        /** The head-plus-optional-discriminator shape [Window] and [Titled] share. */
        private fun <T : SurfaceKey> List<String>.asWindowLike(
            build: (String, String?) -> T,
        ): T? = when (size) {
            1 -> build(this[0], null)
            2 -> build(this[0], this[1])
            else -> null
        }

        /**
         * Derives the key for a surface, applying [RegistryFlags.windowIdentity].
         *
         * A surface that names an [SurfaceDescriptor.origin] is origin-keyed regardless of the
         * flag: the flag is about how windows of one app are split up, and an origin-multiplexed
         * surface has no meaningful window identity to split.
         *
         * **Which kind comes back is which field the key material was read from**, and that is
         * load-bearing rather than cosmetic. A window's `app_id` and its title are both chosen
         * by the program that owns the window, so as long as they shared one slot a program
         * could take another surface's identity by copying a string into the other field
         * (#95). The three kinds below are three namespaces; nothing a descriptor can contain
         * moves a key from one to another.
         */
        fun of(descriptor: SurfaceDescriptor, config: Config): SurfaceKey {
            descriptor.origin?.takeIf { it.isNotBlank() }?.let { return Origin(it) }
            val discriminator = when (config[RegistryFlags.windowIdentity]) {
                WindowIdentity.APP_ID -> null
                WindowIdentity.APP_ID_AND_TITLE -> descriptor.title.orEmpty()
                WindowIdentity.APP_ID_AND_PID -> descriptor.pid?.toString() ?: ""
            }
            descriptor.appId?.takeIf { it.isNotBlank() }
                ?.let { return Window(it, discriminator) }
            // An xwayland window can report no app_id at all. Both ways out are lossy — the
            // title is separable but churns, the fixed key is stable but collapses every such
            // window onto one agent — so it is [RegistryFlags.missingAppId]'s call. Either way
            // the window gets a key, rather than being dropped on the floor.
            if (config[RegistryFlags.missingAppId] == MissingAppId.TITLE) {
                descriptor.title?.takeIf { it.isNotBlank() }
                    ?.let { return Titled(it, discriminator) }
            }
            return Unidentified(discriminator)
        }

        /**
         * The literal an `app_id`-less window used to be keyed on, kept because it is the
         * readable half of [Unidentified]'s slug and tests name it. It is no longer a value
         * any `app_id` can be given to reach that key — see [Unidentified].
         */
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

/**
 * `<kind>:<head>[:<discriminator>]`, with every caller-supplied segment escaped.
 *
 * The kind is a literal this file chooses and the segments are escaped, so no value of [head]
 * or [discriminator] can render a string another kind could also render — which is the
 * property that makes [SurfaceKey.canonical] injective over provenance and not merely over
 * content.
 */
private fun render(kind: String, head: String, discriminator: String?): String =
    "$kind:" + head.escapeSegment() + (discriminator?.let { ":" + it.escapeSegment() } ?: "")

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
 *
 * **What that budget buys is bounded, and #95 is the boundary.** The width prices a *search*
 * for an input whose digest matches another surface's. It says nothing about two inputs that
 * are the same bytes, and that is what a title substituted into the `app_id` slot produced —
 * equal [SurfaceKey.canonical], so equal digest at a cost of zero. Any defence there has to
 * live in what is hashed rather than in how wide the hash is, which is why the field a key was
 * read from is now part of the string this runs over.
 */
private fun String.stableDigest(): String =
    encodeToByteArray().sha256().copyOf(DIGEST_BYTES).toHex()

private const val DIGEST_BYTES = 16
