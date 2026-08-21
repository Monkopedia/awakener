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
    /**
     * Whether the compositor considers this the surface the user is working in.
     *
     * Here because the hotkey has no other way to say "this window". A binding invoked from a
     * key press names no surface at all — the user's answer to "which one" is where their focus
     * already is — so without this the entry point would need either an argument the hotkey
     * cannot supply or a fourth compositor call to ask. It is a fact about the snapshot rather
     * than a durable one, which is why it is deliberately absent from [descriptor]: two windows
     * of one app are the same surface whichever of them is focused right now.
     *
     * Defaulted so that every existing construction site keeps compiling and, more to the point,
     * so that an implementation with no notion of focus reports the honest answer rather than
     * having to invent one.
     */
    val focused: Boolean = false,
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
 * The compositor session behind [WindowManager.changes] ended: the connection the stream was
 * riding on died rather than being closed by the collector.
 *
 * It exists because the two are otherwise the same observation — a stream that stops producing —
 * and they want opposite responses. Nothing happening is the desktop's normal resting state; the
 * compositor going away means every handle, every dock and the whole in-memory view of the tree
 * describes a session that no longer exists. A collector that cannot tell them apart treats the
 * second as the first and keeps waiting, which is what this is here to prevent.
 *
 * Deliberately compositor-agnostic: a collector learns that the session ended, not what was
 * speaking on the other end of the socket.
 */
class CompositorSessionEnded(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Where a [DockHandle.focus] call is asking focus to end up.
 *
 * The two used to be separate methods — `focus()` and `settleFocus()` — and merging them is the
 * `DockHandle` half of holding the interface at three calls. What the merge does **not** do is
 * remove a branch: [DOCK] focuses the dock unconditionally and fails if it is gone, [RESTING]
 * consults `wm.focus.resting` and does nothing at all if the node it picks has left the tree.
 * The `when` that used to be the choice of method is now a `when` inside one, and the divergent
 * tolerance of a missing node is the part a caller has to read the enum to learn.
 */
enum class FocusTarget {
    /** The dock itself — a hotkey invocation on an already-bound surface, raising its panel. */
    DOCK,

    /**
     * Whichever child `wm.focus.resting` names, so a later tab switch lands where the flag says.
     *
     * sway remembers the last focused child per container, so a tab left resting on the dock
     * means the next switch into that tab puts the user's keystrokes into the agent panel
     * instead of the application.
     */
    RESTING,
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
interface DockHandle {
    val surface: SurfaceId
    val agent: AgentId
    val dockId: SurfaceId

    /** Puts focus where [target] says. */
    suspend fun focus(target: FocusTarget = FocusTarget.DOCK)

    /** Tears the dock down and, per flags, normalises the container it leaves behind. */
    suspend fun detach()
}

/**
 * One bindable window and what awakener currently has bound to it.
 *
 * The pair exists because [WindowManager.resolve] answers both questions in one call now that
 * enumeration is not a call of its own. **The [agent] half is not free**: producing it costs one
 * `:registry` lookup per window, and under the default `registry.store.reload=BEFORE_READ` every
 * one of those re-reads the bindings file. A caller that only wants the window list — the hotkey
 * path, which needs the focused surface and then does its own binding work — pays that for an
 * answer it discards.
 */
data class Resolution(
    val surface: Surface,
    /** Null means a Drab: a window with nothing bound to it. */
    val agent: AgentId?,
)

/**
 * The compositor-agnostic binding interface.
 *
 * Deliberately tiny — `resolve`, `attach`, and change notification. Nothing above this may
 * learn which compositor is in use.
 *
 * **Every call here, and every call on the [DockHandle]s it hands out, is safe to make
 * concurrently.** Two hotkeys pressed at once are an ordinary case — one on a Drab, which
 * attaches, and one on a bound surface, which focuses that surface's dock — and a handle's
 * `detach()` runs on whatever coroutine happens to call it. None of these is a
 * single compositor operation, so an implementation owes callers whatever serialisation that
 * takes: one hotkey landing in the middle of another must not be able to stand a dock up in
 * somebody else's tab.
 */
interface WindowManager {
    /**
     * What awakener has bound to [surface], or — with [surface] null — to every bindable window.
     *
     * Answered from the durable registry, so it resolves the same way after a reboot as it did
     * before one — a window is looked up by what outlives it, not by its compositor handle.
     *
     * ### The null argument is enumeration, and it is a second behaviour behind one name
     *
     * `surfaces()` used to be its own call, argued in this KDoc as "enumeration rather than a
     * fourth behaviour — it is how a caller obtains a [SurfaceId] to resolve in the first place".
     * Folding it in holds the interface at three, and the fold is what the two modes cost:
     *
     * - **A null [surface] enumerates**, so it must apply the dock filter — a dock is a genuine
     *   tree node and reporting one as bindable is what mints an agent for an agent panel. A
     *   non-null [surface] does not filter, because the durable answer for a window must not
     *   depend on session-scoped state (#52). One name, two predicates.
     * - **The result is a list either way.** A caller asking about one window writes
     *   `resolve(id).firstOrNull()?.agent`, where it used to write `resolve(id)`. Empty means
     *   "no such window", which is a different fact from "a window with no agent" and is now
     *   carried by the list's length rather than by the value's nullity.
     * - **Enumeration now costs a registry lookup per window**, because the one return type has
     *   to carry the agent. See [Resolution].
     */
    suspend fun resolve(surface: SurfaceId? = null): List<Resolution>

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

    /**
     * Every window change the compositor reports, for as long as it is there to report them.
     *
     * **The stream says when it is over, and why.** It completes normally when there is nothing
     * more to say — the collector stopped, or events are switched off — and fails with
     * [CompositorSessionEnded] when the session it was reading from died. Going quiet is not one
     * of the endings: an idle desktop is a live stream with nothing on it, which is the normal
     * resting state and must stay distinguishable from a compositor that is gone.
     */
    val changes: Flow<SurfaceChange>
}
