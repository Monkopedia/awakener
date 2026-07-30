package com.monkopedia.awakener.wm

import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.SurfaceDescriptor
import kotlinx.coroutines.flow.Flow

/** A window that can have an agent bound to it. Docks are excluded by construction. */
data class Surface(
    val id: SurfaceId,
    val appId: String?,
    val title: String?,
    val pid: Int?,
) {
    /**
     * The compositor-agnostic facts `:registry` keys a durable binding on.
     *
     * [id] is deliberately absent: sway mints a fresh `con_id` every time a window maps, so a
     * binding keyed on it would be forgotten at the next login — which is the exact failure the
     * registry exists to prevent.
     */
    val descriptor: SurfaceDescriptor get() = SurfaceDescriptor(appId, title, pid)
}

/**
 * A live compositor handle. Valid only for as long as the window is mapped; anything that has
 * to survive a restart uses `SurfaceKey` instead.
 */
@JvmInline
value class SurfaceId(val raw: Long)

/** How to bring a dock into being for a surface. */
data class DockSpec(
    /**
     * The `app_id` the dock window will report, or under
     * [DockIdentity.PER_SURFACE_APP_ID] the prefix it is derived from. It must be predictable
     * *before* the window exists, because sway matches focus rules at map time — this is what
     * makes [WmFlags.dockFocusOnMap] expressible at all.
     *
     * It is emphatically *not* an identifier: every dock is the same panel program, so all of
     * them report the same `app_id` unless [WmFlags.dockIdentity] says otherwise.
     */
    val appId: String,
    /**
     * Command sway runs to produce the dock window. Any occurrence of [APP_ID_PLACEHOLDER] is
     * replaced with the `app_id` this dock is expected to report, which is how a dock program
     * gets told the per-surface name under [DockIdentity.PER_SURFACE_APP_ID].
     */
    val command: String,
) {
    companion object {
        const val APP_ID_PLACEHOLDER = "{app_id}"
    }
}

sealed interface SurfaceChange {
    val id: SurfaceId

    data class Appeared(override val id: SurfaceId, val surface: Surface) : SurfaceChange
    data class Vanished(override val id: SurfaceId) : SurfaceChange
    data class Focused(override val id: SurfaceId) : SurfaceChange
}

/**
 * A live dock bound to a surface.
 *
 * Teardown lives here rather than as a `detach` on [WindowManager] deliberately: the design's
 * working agreement holds the interface to three calls, and a handle returned by `attach` is
 * the natural owner of its own lifetime. It is not optional politeness — sway leaves both the
 * dock and its split container standing when a surface dies, so something must close this.
 *
 * Every method here is covered by [WindowManager]'s concurrency contract.
 */
interface DockHandle : AutoCloseable {
    val surface: SurfaceId
    val agent: AgentId
    val dockId: SurfaceId

    /** Raises the dock and focuses it, for a hotkey invocation on an already-bound surface. */
    suspend fun focus()

    /** Applies the resting-focus rule, so a later tab switch lands where the flag says. */
    suspend fun settleFocus()

    /** Tears the dock down and, per flags, normalises the container it leaves behind. */
    suspend fun detach()

    override fun close() {
        // AutoCloseable for try-with-resources at call sites that cannot suspend; the real
        // work is in detach(). Implementations override this to bridge.
    }
}

/**
 * The compositor-agnostic binding interface.
 *
 * Deliberately tiny — `resolve`, `attach`, and change notification. Nothing above this may
 * learn which compositor is in use. [surfaces] is enumeration rather than a fourth behaviour:
 * it is how a caller obtains a [SurfaceId] to resolve in the first place.
 *
 * **Every call here, and every call on the [DockHandle]s it hands out, is safe to make
 * concurrently.** Two hotkeys pressed at once are an ordinary case — one on a Drab, which
 * attaches, and one on a bound surface, which focuses that surface's dock — and closing a handle
 * already tears its dock down from whatever coroutine happens to run it. None of these is a
 * single compositor operation, so an implementation owes callers whatever serialisation that
 * takes: one hotkey landing in the middle of another must not be able to stand a dock up in
 * somebody else's tab.
 */
interface WindowManager {
    suspend fun surfaces(): List<Surface>

    /**
     * The agent bound to [surface], or null if this is a Drab.
     *
     * Answered from the durable registry, so it resolves the same way after a reboot as it did
     * before one — a window is looked up by what outlives it, not by its compositor handle.
     */
    suspend fun resolve(surface: SurfaceId): AgentId?

    /**
     * Stands a dock up beside [surface] and records the binding it is standing for.
     *
     * @param agent the agent to bind, or null — the hotkey case — to use whatever this surface
     * is already bound to, minting a Lifeless if it is still a Drab.
     * @return a handle whose [DockHandle.agent] is the agent actually bound, which under
     * `registry.binding.rebind_policy=KEEP` is **not** necessarily the one passed in: a caller
     * holding a stale agent must not be able to strand the residue accumulated under the
     * existing one.
     */
    suspend fun attach(surface: SurfaceId, dock: DockSpec, agent: AgentId? = null): DockHandle

    val changes: Flow<SurfaceChange>
}
