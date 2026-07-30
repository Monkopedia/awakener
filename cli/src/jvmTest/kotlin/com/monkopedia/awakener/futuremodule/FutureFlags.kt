package com.monkopedia.awakener.futuremodule

import com.monkopedia.awakener.config.Flags

/**
 * Stands in for a module that does not exist yet.
 *
 * Nothing references this object — not the CLI, not the build file, not even the test that
 * asserts its flag shows up (which spells the key out as a literal on purpose). It is here so
 * that "a module added later is visible to `awakener-config list`" is checked by the same
 * mechanism a real fourth module would rely on: being on the classpath, in awakener's package,
 * with a name ending in `Flags`. Wire the flag set up by hand and this test stops proving it.
 */
object FutureFlags {
    val knob = Flags.boolean(
        "futuremodule.knob",
        true,
        "A flag belonging to a module nobody has told the CLI about.",
    )
}
