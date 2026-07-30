package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.ConfigCli
import com.monkopedia.awakener.config.FileConfigStore
import com.monkopedia.awakener.config.Flags
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
    Flags.requireLoaded(RegistryFlags)
    val configStore = FileConfigStore(ConfigCli.defaultPath())
    val store = FileBindingStore(configStore, SpanreedCli(configStore))
    exitProcess(RegistryCli.run(args, store, ::println))
}
