package com.monkopedia.awakener.wm

import kotlinx.coroutines.flow.Flow

/** A window that can have an agent bound to it. Docks are excluded by construction. */
data class Surface(
    val id: SurfaceId,
    val appId: String?,
    val title: String?,
    val pid: Int?,
)

@JvmInline
value class SurfaceId(val raw: Long)

@JvmInline
value class AgentId(val raw: String)

/** How to bring a dock into being for a surface. */
data class DockSpec(
    /**
     * The `app_id` the dock window will report. It must be predictable *before* the window
     * exists, because sway matches focus rules at map time — this is what makes
     * [WmFlags.dockFocusOnMap] expressible at all.
     */
    val appId: String,
    /** Command sway runs to produce the dock window. */
    val command: String,
)

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
 */
interface WindowManager {
    suspend fun surfaces(): List<Surface>

    suspend fun resolve(surface: SurfaceId): AgentId?

    suspend fun attach(surface: SurfaceId, agent: AgentId, dock: DockSpec): DockHandle

    val changes: Flow<SurfaceChange>
}
