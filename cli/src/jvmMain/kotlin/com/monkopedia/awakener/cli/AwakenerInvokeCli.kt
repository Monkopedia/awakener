package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.ConfigCli
import com.monkopedia.awakener.config.FileConfigStore
import com.monkopedia.awakener.connector.ConnectorFlags
import com.monkopedia.awakener.connector.ConnectorOutcome
import com.monkopedia.awakener.connector.FileConnectorCatalog
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
 * Every invocation starts, acts and exits. Nothing is *held* between presses — no `DockHandle`
 * survives one — but a handle can be recovered from the compositor, which is what
 * `invoke.when_animated=TOGGLE_HIDE` rests on: `SwayWindowManager.dockFor` reads the panel back
 * out of the tree by its mark, so a second press can hide it and a third bring it back, with the
 * agent running throughout. That works across an awakener restart too, because the mark is on the
 * window rather than in this process.
 *
 * What is still out of reach is anything needing state this process did not put in the tree —
 * swapping which agent a panel holds, or reacting to something between presses. Those are what a
 * daemon adds; nothing here should read as though they already work.
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
                var connector: ConnectorChoice = ConnectorChoice.FromConfig
                var target: SurfaceId? = null
                var index = 1
                while (index < args.size) {
                    val arg = args[index]
                    when {
                        arg == "--no-connector" -> connector = ConnectorChoice.None
                        arg == "--connector" -> {
                            val name = args.getOrNull(index + 1)
                                ?: return usage(out, "--connector needs a name")
                            index++
                            connector = named(name) ?: return usage(
                                out,
                                "'$name' is not usable as a connector name: a connector is one " +
                                    "directory on connector.dir, so the name may not contain a " +
                                    "path separator, a space, or be . or ..",
                            )
                        }

                        arg.startsWith("--connector=") -> {
                            val name = arg.substringAfter('=')
                            connector = named(name) ?: return usage(
                                out,
                                "'$name' is not usable as a connector name: a connector is one " +
                                    "directory on connector.dir, so the name may not contain a " +
                                    "path separator, a space, or be . or ..",
                            )
                        }

                        // Refused rather than ignored. An unrecognised flag on a hotkey path is
                        // almost always a misspelt one, and silently treating `--connectr=x` as
                        // a surface id — which is what a bare else that fell through to the
                        // number parse would do — reports the wrong problem.
                        arg.startsWith("-") -> return usage(out, "unknown option '$arg'")

                        target != null -> return usage(out, "more than one surface id given")

                        else -> target = arg.toLongOrNull()?.let(::SurfaceId)
                            ?: return usage(out, "surface id must be a number, got '$arg'")
                    }
                    index++
                }
                report(awakening.invoke(target, connector), out)
            }

            "connectors" -> {
                val listing = awakening.connectors()
                if (listing.connectors.isEmpty() && listing.broken.isEmpty()) {
                    out("no connectors")
                    // Where it looked, because "none installed" and "looking in the wrong
                    // place" are the same line otherwise, and connector.dir is a flag.
                    listing.searched.forEach { out("  searched $it") }
                }
                listing.connectors.forEach { connector ->
                    val provisional = if (connector.manifest.provisional) "  [provisional]" else ""
                    out("${connector.name}$provisional")
                    connector.manifest.description.takeIf { it.isNotBlank() }
                        ?.let { out("    $it") }
                    if (connector.manifest.provisional) {
                        out("    provisional: ${connector.manifest.provisionalReason}")
                    }
                    connector.manifest.requires.takeIf { it.isNotEmpty() }
                        ?.let { out("    requires ${it.joinToString(", ")}") }
                    out("    ${connector.dir}")
                    out("    ${connector.files.size} file(s): ${connector.files.joinToString(" ")}")
                }
                listing.broken.forEach { out("error: ${it.name}: ${it.dir}: ${it.reason}") }
                // Non-zero when something on the path looks like a connector and is not: this
                // is the command someone runs to find out why `--connector x` refused.
                if (listing.broken.isEmpty()) 0 else 1
            }

            "reap" -> {
                awakening.reap()
                out("swept")
                0
            }

            else -> usage(out, "unknown command '${args[0]}'")
        }

    /** A `--connector` value, or null when it is not a name a connector directory could have. */
    private fun named(name: String): ConnectorChoice? =
        if (ConnectorFlags.isConnectorName(name)) ConnectorChoice.Named(name) else null

    /**
     * One line, safe to print, for a string whose bytes the window chose.
     *
     * `awakener-invoke list` prints an `app_id` and a `SurfaceKey`, and both carry whatever the
     * program owning the window declared — `SurfaceKey.escapeSegment` escapes `%` and `:` and
     * passes newlines through, because its job is injectivity rather than safety. A window whose
     * `app_id` is four lines of Markdown therefore rendered as four lines of this command's
     * output, one of them beginning `# NEW INSTRUCTIONS FOR THE AGENT`.
     *
     * That is the same class `connector.window_facts` closes for rendered files, one layer over:
     * a hotkey press writes to a terminal, but `awakener-invoke list` is exactly the command an
     * agent runs to find out what is on the desktop, and its stdout is then something an agent
     * reads. Found by re-running the reviewer's own payload against the fixed build rather than
     * by being told, which is the argument for fixing a class rather than a case.
     *
     * Not a flag. A one-line-per-surface listing that a window can turn into four lines is wrong
     * for a human reader too, so there is no second behaviour to offer — this is the report being
     * correct rather than a choice about it.
     */
    internal fun oneLine(value: String): String = buildString(value.length) {
        value.forEach { c ->
            when {
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                // C0 and DEL, escaped as `\xNN` rather than dropped: the value stays reversible,
                // so two surfaces differing only in a control character still print differently.
                // This is a listing people match windows against.
                c.code < 0x20 || c.code == 0x7F -> append("\\x%02X".format(c.code))
                else -> append(c)
            }
        }
    }

    private fun report(standing: Standing, out: (String) -> Unit) {
        val focus = if (standing.surface.focused) "* " else "  "
        out(
            "$focus${standing.surface.id.raw}  " +
                (standing.surface.appId?.let(::oneLine) ?: "(no app_id)"),
        )
        out("    ${oneLine(standing.key.canonical)}")
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
                    "${awakened.binding.agentId} for ${oneLine(awakened.key.canonical)}",
            )
            out("  SPANREED_AGENT_NAME=${awakened.binding.spanreedName}")
            out("  dock ${awakened.dock.dockId.raw} beside surface ${awakened.surface.raw}")
            reportConnector(awakened.connector, out)
            0
        }

        is Awakened.AlreadyAnimated -> {
            out("${awakened.binding.agentId} is already animated; standing no second panel")
            out("  ${oneLine(awakened.key.canonical)}")
            // Zero, not a failure: the hotkey did exactly what the flag asked of it.
            0
        }

        // `agent.raw` and not `agent`: AgentId is a value class over a String, so interpolating
        // it prints `AgentId(raw=agent-…)`. Measured through the shipped launcher, which is the
        // only place that would have shown it — every other reader of an agent here goes through
        // `Binding.agentId`, which is already the String.
        is Awakened.Hidden -> {
            out("hid dock ${awakened.dock.dockId.raw} beside surface ${awakened.surface.raw}")
            out("  ${awakened.dock.agent.raw} is still running; press again to bring it back")
            0
        }

        is Awakened.Restored -> {
            out(
                "restored dock ${awakened.dock.dockId.raw} beside surface " +
                    "${awakened.surface.raw}",
            )
            out("  ${awakened.dock.agent.raw} is back on screen")
            0
        }

        is Awakened.ConnectorRefused -> {
            out(
                "connector '${awakened.refusal.name}' refused, so no panel was stood up: " +
                    oneLine(awakened.refusal.reason),
            )
            out(
                "  ${oneLine(awakened.key.canonical)} stays bound to " +
                    awakened.binding.agentId,
            )
            // Distinct from 1 (nothing to awaken) and 2 (bad usage): the surface was found, the
            // agent was recalled, and the one thing that did not happen is the connector.
            3
        }

        is Awakened.NoSurface -> {
            out("nothing to awaken: ${awakened.reason}")
            1
        }
    }

    private fun reportConnector(outcome: ConnectorOutcome, out: (String) -> Unit) {
        when (outcome) {
            // Silence is the point. An unconfigured press prints exactly what it always did.
            is ConnectorOutcome.None -> Unit
            is ConnectorOutcome.Applied -> {
                out("  connector ${outcome.name} -> ${outcome.target}")
                out("    ${outcome.written.size} file(s) written from ${outcome.dir}")
                outcome.kept.takeIf { it.isNotEmpty() }?.let {
                    out("    ${it.size} kept (connector.existing=KEEP): ${it.joinToString(" ")}")
                }
                outcome.warnings.forEach { out("    warning: $it") }
            }

            is ConnectorOutcome.Skipped -> out("  warning: ${outcome.reason}")
            // Never reaches here: `invoke` turns a refusal into Awakened.ConnectorRefused before
            // a dock exists. Exhaustive rather than `else` so that a new outcome is a compile
            // error at this reporting site rather than a silently unreported one.
            is ConnectorOutcome.Refused -> out("  error: ${outcome.reason}")
        }
    }

    private fun usage(out: (String) -> Unit, error: String): Int {
        out("error: $error")
        out(
            "usage: awakener-invoke [list | invoke [--connector <name> | --no-connector] " +
                "[<surface id>] | connectors | reap]",
        )
        out("  invoke with no id acts on the focused window, which is what a hotkey has")
        out("  --connector names a directory on connector.dir; `connectors` lists them")
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
                        dockFor = wm::dockFor,
                        // The snapshot, not the store. One press is one operation, and the
                        // catalog reads `connector.*` — a reload landing between "which
                        // connector" and "where does it write" would materialise half of one
                        // deployment's answer and half of another's.
                        connectors = FileConnectorCatalog(store.config.value),
                    ),
                    ::println,
                )
            } finally {
                // Where the model was written, when that is somewhere another local user could
                // have got to first. In the `finally` because a press that then failed for some
                // other reason has still created the directory, and on stderr for the reason
                // every warning here is: stdout is what the press reports about its surface.
                bindings.residueExposure?.let { System.err.println("warning: $it") }
                // And whether a `registry.residue.layout` flip left this surface's earlier model
                // in a file this build no longer opens. Beside the exposure warning because it
                // has the same shape: a condition only the store can see, on the one call that
                // was about to write the model somewhere.
                bindings.residueStranded?.let { System.err.println("warning: $it") }
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
