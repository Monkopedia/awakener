package com.monkopedia.awakener.config

/** How the flag-declaring classes are found before anything enumerates flags. */
enum class FlagDiscoveryMode {
    /**
     * Walk the classpath for awakener classes whose name ends in `Flags` and load them. Nothing
     * to remember when a module is added, which is the only property that keeps `list` honest
     * over time — a hand-maintained list drifts the first time someone who has not read this
     * adds a module.
     */
    CLASSPATH,

    /**
     * Load only the classes named in [ConfigFlags.declarations]. No classpath walk: the escape
     * hatch for a deployment where scanning finds the wrong thing, or nothing.
     */
    DECLARED,
}

/** Flags governing configuration itself. */
object ConfigFlags {
    val discovery = Flags.enum(
        "config.flags.discovery",
        FlagDiscoveryMode.CLASSPATH,
        "How flag declarations are found. Scanning the classpath needs no bookkeeping when a " +
            "module is added; the declared list needs no classpath walk.",
    )

    val declarations = Flags.string(
        "config.flags.declarations",
        "",
        "Comma-separated fully-qualified names of flag-declaring classes. Loaded in addition " +
            "to whatever the classpath scan finds, and the sole source under the DECLARED mode.",
    )
}
