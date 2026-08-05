package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.ConfigCli
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * `awakener-registry` — read and repair the durable bindings.
 *
 * The memory model's promise is that the durable layer is inspectable when an agent gets you
 * wrong, and "which agent is this window even talking to" is the first question you would ask.
 * `forget` is the repair: it drops a binding so the surface mints a fresh Lifeless, without
 * touching the residue the old one accumulated.
 */
object RegistryCli {
    fun run(args: Array<String>, store: FileBindingStore, out: (String) -> Unit): Int {
        store.loadError?.let {
            out("error: $it")
            out("refusing to write until this is resolved; move the file aside to start over")
            return 1
        }
        store.unreadableKeys.forEach { out("warning: unreadable key '$it' (kept, not resolved)") }

        return when (args.firstOrNull() ?: "list") {
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
                val had = runBlocking { store.unbind(key) }
                out(if (had) "forgot ${key.canonical}; residue left in place" else "not bound")
                if (had) 0 else 1
            }

            else -> usage(out, "unknown command '${args[0]}'")
        }
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
