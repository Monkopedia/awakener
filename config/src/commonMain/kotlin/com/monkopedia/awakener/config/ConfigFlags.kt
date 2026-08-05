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

/** What a value that decodes but breaks its flag's [Requirement] is read as. */
enum class InvalidValue {
    /**
     * The flag's declared default, which is what a value that fails to *decode* already does.
     * One rule for both halves of "unusable", and the one rule is the one the config file's
     * contract already states — so a flag out of range behaves like a flag misspelt, and the
     * default is by construction a value the system is correct under.
     */
    DEGRADE,

    /**
     * The nearest value the requirement allows: `wm.dock.size_ppt: 150` becomes 100. Closer to
     * what a number out of range usually meant, and it is what the modules reading these flags
     * did before anything validated them — they coerced at the read site, so nothing threw and
     * nothing was said. Available for a flag whose requirement can name a nearest value; the
     * rest fall back to the default. Its cost is that two different typed values can mean the
     * same thing, so a mistake is that much less visible in behaviour.
     */
    CLAMP,

    /**
     * The typed value, unchanged. Nothing is degraded and the problem is still reported — for
     * driving a module past a range its author asserted, and for finding out whether a range is
     * the reason something behaves oddly without editing the declaration.
     */
    KEEP,
}

/** What a `set` or `unset` does when the file it must rewrite cannot be read. */
enum class UnreadableWrite {
    /**
     * Refuse, naming the file and the reason. The file is the source of truth and is expected to
     * hold hand-authored values, so overwriting one nothing has managed to read discards edits
     * that may only be unreadable for as long as a permission is wrong.
     */
    REFUSE,

    /**
     * Write the last contents this process read out of the file, plus the change being made,
     * replacing whatever is there now. The recovery path when the file is genuinely damaged,
     * since `awakener-config set` is otherwise unusable until it is fixed by hand — at the price
     * of being the one thing that discards it. It is the last *file* contents rather than the
     * live snapshot on purpose: the snapshot has `AWAKENER_*` overrides merged into it, and a
     * rewrite from that would make a one-off environment variable permanent.
     */
    REWRITE,
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

    val invalidValue = Flags.enum(
        "config.validation.invalid_value",
        InvalidValue.DEGRADE,
        "What a value that decodes but breaks its flag's stated requirement is read as. " +
            "DEGRADE gives it the same treatment as a value that will not decode at all, which " +
            "is what the config contract already promises and what makes an unconfigured — or " +
            "a mis-edited — system correct. CLAMP takes the nearest allowed value instead, " +
            "which is what the modules reading these flags did on their own before anything " +
            "checked, and is usually closer to what a number out of range meant; a flag whose " +
            "requirement cannot name a nearest value degrades under it as well. KEEP applies " +
            "the value as typed and only reports it, for driving a module past a range " +
            "deliberately. All three report through `awakener-config`; this decides only which " +
            "value is used, never whether the mistake is mentioned. It is itself unconstrained, " +
            "deliberately: the flag that decides what a broken value means cannot be one.",
    )

    val unreadableWrite = Flags.enum(
        "config.store.unreadable_write",
        UnreadableWrite.REFUSE,
        "What `set` and `unset` do when the config file cannot be read back before rewriting " +
            "it. REFUSE says so and changes nothing, because a file that is unreadable this " +
            "second — a permission put on by hand, a filesystem hiccup — still holds every " +
            "value that was typed into it, and a rewrite from memory is how those are lost. " +
            "REWRITE replaces it with the values currently live, which is the way back when the " +
            "file is genuinely damaged and `set` would otherwise be unusable until it is " +
            "repaired by hand. Neither can take the process down: this used to be an unhandled " +
            "IOException on the write path.",
    )

    /**
     * The one cross-flag rule `:config` has of its own, and the reason [Flags.constraint] is
     * exercised by the module that declares it rather than only by a test. `FlagDiscovery`
     * reports this too, but only on the run that performs discovery — a later hand edit that
     * flips the mode and leaves the list empty reaches no discovery, and until this existed it
     * reached nothing at all.
     */
    val declaredNeedsDeclarations = Flags.constraint(
        "config.flags.declarations",
        "must not be empty when config.flags.discovery is DECLARED",
    ) { config ->
        if (config[discovery] == FlagDiscoveryMode.DECLARED && config[declarations].isBlank()) {
            "empty while ${discovery.key} is DECLARED, so no flags are found at all and every " +
                "key in this file reads as one nothing declares"
        } else {
            null
        }
    }
}
