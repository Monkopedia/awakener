package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.ConfigCli
import com.monkopedia.awakener.config.FileConfigStore
import com.monkopedia.awakener.registry.FileBindingStore
import com.monkopedia.awakener.registry.SpanreedCli
import com.monkopedia.awakener.wm.SurfaceId
import com.monkopedia.awakener.wm.SwaySocket
import com.monkopedia.awakener.wm.SwayWindowManager
import com.monkopedia.awakener.wm.WmFlags
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * `awakener-invoke` — the hotkey.
 *
 * `invoke` with no argument acts on the focused window, which is what lets the whole binding be
 * reached as one line of sway config:
 *
 * ```
 * bindsym $mod+a exec awakener-invoke invoke
 * ```
 *
 * ### It is a one-shot process, and that bounds what it can promise
 *
 * Every invocation starts, acts and exits. Nothing is held between presses, so this cannot raise
 * a panel that is already standing or take one down on a later press — a `DockHandle` is what
 * knows which node a dock is, and this process has none from last time. What it can do is decline
 * to stand a second panel beside a Lifeless that is already animated, which is what
 * `invoke.when_animated` is for, and take down docks whose surface has gone, which is `reap`.
 * Raising and swapping are what a daemon adds; nothing here should read as though they already
 * work.
 *
 * That shape is also what keeps this on the right side of the design brief's "no unattended
 * autonomous action". This process does nothing until a key is pressed, and then it exits. There
 * is no timer, no loop and no schedule — the only thing that runs between presses is the repair
 * collector `:wm` starts, which is parked on a socket read and reacts to the user closing a
 * window.
 */
object AwakenerInvokeCli {
    suspend fun run(args: Array<String>, awakening: Awakening, out: (String) -> Unit): Int =
        when (args.firstOrNull() ?: "list") {
            "list" -> {
                val standing = awakening.list()
                if (standing.isEmpty()) out("no surfaces")
                standing.forEach { report(it, out) }
                0
            }

            "invoke" -> {
                val target = args.getOrNull(1)?.let { raw ->
                    raw.toLongOrNull()?.let(::SurfaceId)
                        ?: return usage(out, "surface id must be a number, got '$raw'")
                }
                report(awakening.invoke(target), out)
            }

            "reap" -> {
                awakening.reap()
                out("swept")
                0
            }

            else -> usage(out, "unknown command '${args[0]}'")
        }

    private fun report(standing: Standing, out: (String) -> Unit) {
        val focus = if (standing.surface.focused) "* " else "  "
        out("$focus${standing.surface.id.raw}  ${standing.surface.appId ?: "(no app_id)"}")
        out("    ${standing.key.canonical}")
        out(
            "    " + when (val binding = standing.binding) {
                // The vocabulary is load-bearing: a Drab is a window with nothing bound to it,
                // and it is what a hotkey turns into a Lifeless.
                null -> "drab (no agent bound)"
                else -> "agent ${binding.agentId}" +
                    (if (standing.animated) "  [animated]" else "  [not animated]")
            },
        )
    }

    private fun report(awakened: Awakened, out: (String) -> Unit): Int = when (awakened) {
        is Awakened.Animated -> {
            out(
                (if (awakened.minted) "minted " else "recalled ") +
                    "${awakened.binding.agentId} for ${awakened.key.canonical}",
            )
            out("  SPANREED_AGENT_NAME=${awakened.binding.spanreedName}")
            out("  dock ${awakened.dock.dockId.raw} beside surface ${awakened.surface.raw}")
            0
        }

        is Awakened.AlreadyAnimated -> {
            out("${awakened.binding.agentId} is already animated; standing no second panel")
            out("  ${awakened.key.canonical}")
            // Zero, not a failure: the hotkey did exactly what the flag asked of it.
            0
        }

        is Awakened.NoSurface -> {
            out("nothing to awaken: ${awakened.reason}")
            1
        }
    }

    private fun usage(out: (String) -> Unit, error: String): Int {
        out("error: $error")
        out("usage: awakener-invoke [list | invoke [<surface id>] | reap]")
        out("  invoke with no id acts on the focused window, which is what a hotkey has")
        return 2
    }
}

/**
 * The entry point, and the one place that turns a raised failure into something a person can
 * read. See [awaken] for the wiring; the only thing that happens here is the decision about
 * what a hotkey press says when the bind path refuses.
 */
fun main(args: Array<String>) {
    // Warnings go to stderr rather than stdout: this one's stdout is what a hotkey press reports
    // about the surface it acted on.
    //
    // Outside the handler below, and it is the one thing that is: the handler reads
    // `invoke.failure.detail` off this store, so there is nothing for it to consult until this
    // returns. It is also the one call here that is contracted not to raise — `:config`'s whole
    // rule is that a hand-edited file degrades to defaults and reports through `loadError` and
    // `Config.problems` rather than taking the process down.
    val store = ConfigCli.bootstrap(out = System.err::println)

    val status = try {
        awaken(args, store)
    } catch (e: Exception) {
        // The composition root is the only place that can see every raise from `:wm`,
        // `:registry` and `:config` at once, so it is where the decision belongs. Some of what
        // arrives here is deliberate — `liveAgents` refuses to answer an unreadable bus as an
        // empty one, and `mint` refuses an id spanreed did not issue — and a deliberate refusal
        // that reaches the user as a JVM stack trace on a key press is a report nobody reads.
        //
        // `Exception` and not `Throwable`, the same width `ConfigCli` uses: everything raised on
        // purpose is one, and an `Error` is not a thing this process can report its way out of.
        System.err.println("error: ${e.message ?: e.toString()}")
        if (store.config.value[InvokeFlags.failureDetail] == FailureDetail.TRACE) {
            e.printStackTrace(System.err)
        }
        1
    }
    exitProcess(status)
}

/**
 * The composition root, and the only place that knows both which store and which compositor.
 *
 * [Awakening] is handed a `WindowManager` and two lambdas rather than the concrete types, so the
 * loop itself stays incurious about sway and about how residue is laid out on disk. Everything
 * that has to know is here.
 *
 * Split out of [main] so that every raise inside it — from `:wm`, `:registry` or `:config`, and
 * including the ones made on purpose — passes through one handler on the way out.
 */
private fun awaken(args: Array<String>, store: FileConfigStore): Int {
    val spanreed = SpanreedCli(store)
    val bindings = FileBindingStore(store, spanreed)
    bindings.loadError?.let {
        System.err.println("error: $it")
        System.err.println("refusing to bind against a bindings file this build cannot read")
        exitProcess(1)
    }

    // SupervisorJob so that a repair collector failing under `wm.collector.failure=PROPAGATE`
    // does not take the invocation down with it; the manager documents that the scope it is
    // given must tolerate a child failing under that flag.
    val scope = CoroutineScope(SupervisorJob())
    // Resolved inside the lambda rather than once above it, because the manager calls this again
    // for every session: this process is one-shot today, so the second call cannot happen here,
    // but a connect that captured one path is exactly what made reconnection impossible (#33) and
    // it should not be reintroduced by the composition root.
    val wm = SwayWindowManager(
        connect = {
            val config = store.config.value
            SwaySocket.connect(
                config[WmFlags.socketPath].ifBlank { null },
                config[WmFlags.socketDiscovery],
            )
        },
        store = store,
        registry = bindings,
        scope = scope,
    )

    return try {
        runBlocking {
            try {
                AwakenerInvokeCli.run(
                    args,
                    Awakening(
                        wm = wm,
                        registry = bindings,
                        bus = spanreed,
                        store = store,
                        prepareResidue = { bindings.prepareResidue(it) },
                        reapOrphans = wm::reapOrphans,
                    ),
                    ::println,
                )
            } finally {
                // Where the model was written, when that is somewhere another local user could
                // have got to first. In the `finally` because a press that then failed for some
                // other reason has still created the directory, and on stderr for the reason
                // every warning here is: stdout is what the press reports about its surface.
                bindings.residueExposure?.let { System.err.println("warning: $it") }
                // The dock is sway's child, not this process's, so retiring the manager stops its
                // collector and gives up its connection and leaves every panel standing.
                //
                // Reported and not raised: a close that cannot retire its own collector is worth
                // saying out loud, and this process is about to exit either way — turning an
                // invocation that did its job into a failed exit status would tell the user
                // something false about the thing they pressed a key for.
                runCatching { wm.close() }.exceptionOrNull()?.let {
                    System.err.println("warning: ${it.message}")
                }
            }
        }
    } finally {
        scope.cancel()
    }
}
