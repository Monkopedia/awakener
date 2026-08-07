package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.ConfigCli
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * `awakener-registry` — read and repair the durable bindings.
 *
 * The memory model's promise is that the durable layer is inspectable when an agent gets you
 * wrong, and "which agent is this window even talking to" is the first question you would ask.
 * `forget` is the repair: it drops a binding so the surface mints a fresh Lifeless, and says
 * what became of the residue the old one accumulated — kept, archived aside, or deleted, per
 * [RegistryFlags.forgetResidue]. It used to promise "residue left in place" unconditionally,
 * which was true and was also the whole of #60: residue is keyed on the surface rather than on
 * the agent, so the fresh Lifeless was handed the forgotten one's written-down model.
 *
 * Exit codes: 0 forgot it, 1 nothing was bound, 2 the arguments were wrong, **3 the binding is
 * gone but the residue disposal failed** — a distinct code because under
 * [ForgetResidue.DELETE] that is a model the user asked to be rid of which is still on disk,
 * and folding it into 0 would report a repair that half happened as one that did.
 */
object RegistryCli {
    fun run(args: Array<String>, store: FileBindingStore, out: (String) -> Unit): Int {
        store.loadError?.let {
            out("error: $it")
            out("refusing to write until this is resolved; move the file aside to start over")
            return 1
        }
        store.unreadableKeys.forEach { out("warning: unreadable key '$it' (kept, not resolved)") }

        val code = when (args.firstOrNull() ?: "list") {
            "list" -> {
                val bindings = store.bindings.value
                if (bindings.isEmpty()) out("no surfaces bound (${store.path})")
                bindings.forEach { (key, binding) ->
                    out(key.canonical)
                    out("  agent    ${binding.agentId}  (SPANREED_AGENT_NAME=${binding.spanreedName})")
                    out("  residue  ${store.residueLocation(key)}")
                }
                0
            }

            "resolve" -> {
                val key = args.getOrNull(1)?.let(SurfaceKey::parse)
                    ?: return usage(out, "resolve needs a canonical key, e.g. window:firefox")
                val binding = runBlocking { store.resolve(key) }
                    ?: return usage(out, "no binding for ${key.canonical}")
                out(binding.agentId)
                0
            }

            "residue" -> {
                val key = args.getOrNull(1)?.let(SurfaceKey::parse)
                    ?: return usage(out, "residue needs a canonical key")
                out(store.residueLocation(key))
                0
            }

            "forget" -> {
                val key = args.getOrNull(1)?.let(SurfaceKey::parse)
                    ?: return usage(out, "forget needs a canonical key")
                val forget = runBlocking { store.unbind(key) }
                if (!forget.wasBound) {
                    out("not bound")
                    1
                } else {
                    out("forgot ${key.canonical}")
                    // Named rather than implied. The user reaching for this command has just
                    // decided an agent has the wrong idea about them, and the next thing they
                    // need to know is whether the written-down version of that idea is still
                    // there — which under RegistryFlags.forgetResidue it may or may not be.
                    out("  residue  ${forget.residue.describe()}")
                    if (forget.residue is ResidueOutcome.Failed) 3 else 0
                }
            }

            else -> usage(out, "unknown command '${args[0]}'")
        }
        // After the command, not before: a store only finds out it cannot lock when it writes.
        // Non-fatal — the write did happen — but a `forget` that ran with no cross-process
        // exclusion is one another process can undo, and that is the whole of #50.
        store.lockError?.let { out("warning: $it") }
        return code
    }

    /**
     * One line saying where the model now is.
     *
     * Every case names a path, so none of them can be read as any of the others at a glance —
     * "left in place" and "archived" differ by the thing the user is about to go and look at.
     */
    private fun ResidueOutcome.describe(): String = when (this) {
        is ResidueOutcome.Kept -> "left in place at $path"
        is ResidueOutcome.Archived -> "archived to $archive (the fresh agent starts empty)"
        is ResidueOutcome.Deleted -> "deleted from $path"
        is ResidueOutcome.Absent -> "nothing had been written to $path"
        is ResidueOutcome.Failed -> "STILL AT $path — could not be disposed of: $reason"
    }

    private fun usage(out: (String) -> Unit, error: String): Int {
        out("error: $error")
        out("usage: awakener-registry [list | resolve <key> | residue <key> | forget <key>]")
        return 2
    }
}

fun main(args: Array<String>) {
    // The shared bootstrap, not `Flags.requireLoaded(RegistryFlags)` alone: registering this
    // module's own flags and stopping there leaves a snapshot that calls every `wm.*` and
    // `config.*` key in the file one no flag declares (#45). It is launched off `:cli`'s
    // classpath precisely so discovery can see them.
    val configStore = ConfigCli.bootstrap(out = ::println)
    // Same reporting `awakener-config` gives: a snapshot is total, so a bad value silently
    // becomes its default unless somebody says so. This is also what makes the bootstrap above
    // observable — without it, every key of another module's was called unknown in silence.
    configStore.config.value.problems.forEach { println("warning: ${it.key}: ${it.reason}") }
    val store = FileBindingStore(configStore, SpanreedCli(configStore))
    exitProcess(RegistryCli.run(args, store, ::println))
}
