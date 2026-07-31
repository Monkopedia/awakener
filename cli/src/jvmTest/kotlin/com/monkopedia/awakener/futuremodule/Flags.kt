package com.monkopedia.awakener.futuremodule

import com.monkopedia.awakener.config.Flags as Registry

/**
 * A declaring object whose simple name is the registry's.
 *
 * `com.monkopedia.awakener.config.Flags` is skipped by discovery because it is the registry and
 * declares nothing. `Flags` is not a reserved word, though — it is the name a module holding one
 * flag set naturally reaches for, and `:chrome` and `:pairing-mcp` are still unwritten. This
 * object is that shape: a legitimate declarer, in awakener's package, in a module of its own.
 *
 * Nothing references it — not the CLI, not the build file, not the tests that assert its flag,
 * which spell the key out as a literal. Wire it up by hand and it stops proving anything.
 */
object Flags {
    val knob = Registry.boolean(
        "futuremodule.plain_named_knob",
        true,
        "A flag declared by a holder that shares the registry's simple name.",
    )
}
