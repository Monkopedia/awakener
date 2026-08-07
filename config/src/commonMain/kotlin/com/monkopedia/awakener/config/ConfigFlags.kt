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

/** What a watching store does when the file it is watching stops existing. */
enum class MissingFile {
    /**
     * Keep the values last read out of it, and report the disappearance through
     * `FileConfigStore.loadError`.
     *
     * The same rule a malformed file already gets, for the same reason: against a running
     * desktop, reverting every flag at once to its default is the most expensive thing a
     * snapshot can do, and a file that has gone is no more evidence that the operator wanted
     * defaults than a file that will not parse. It is also what makes the reload safe across
     * the gap in a delete-then-write save, where the file is legitimately absent for as long
     * as the writer takes — under [DEFAULTS] a daemon reads every flag at its default for
     * that window and says nothing about it.
     *
     * Its cost is the honest one: a file deleted *on purpose* does not reset a running
     * process, and will not until it restarts or the operator uses `unset`. That is why the
     * disappearance is reported rather than passed over.
     */
    KEEP,

    /**
     * Reload as though the file were empty, so every flag returns to its declared default —
     * for a deployment where removing the file is how a reset is performed, and for making
     * that reset take effect without a restart.
     */
    DEFAULTS,
}

/** What a running watch does with an event batch that says events were lost. */
enum class WatchOverflow {
    /**
     * Re-read the file, exactly as a change to it would.
     *
     * An overflow is the one event that says *something you were meant to see is gone*, and a
     * re-read is total — the store reads the whole file, so one read repairs however many
     * events were dropped. That is why this is the default: the alternative leaves the watch
     * quietest at the moment it has most reason to speak.
     */
    REREAD,

    /**
     * Keep the values last read, and report the loss through `FileConfigStore.loadError`.
     *
     * For a deployment where the snapshot must not move except on a change somebody can point
     * at — an overflow says events were lost, not that this file was among them, so a re-read
     * on one is a reload with no edit behind it. The cost is the honest one: if the file *did*
     * change in the lost batch, nothing picks it up until the next event.
     */
    REPORT,
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
            "REWRITE replaces it with the last contents this process read out of it, plus the " +
            "change being made — and with nothing but the change if it has never managed to " +
            "read the file at all, which is the case for a fresh `awakener-config` and is the " +
            "destructive one to know about. That is the way back when the file is genuinely " +
            "damaged and `set` would otherwise be unusable until it is repaired by hand. It is " +
            "the file's last contents rather than the values currently in effect on purpose: " +
            "those have AWAKENER_* overrides merged into them, and rewriting from them would " +
            "make a one-off environment variable permanent. Set it through the environment " +
            "(AWAKENER_CONFIG_STORE_UNREADABLE_WRITE=REWRITE) when the file is the thing that " +
            "cannot be read — `set` would have to read the file to record it there. Neither " +
            "value can take the process down: this used to be an unhandled IOException.",
    )

    val watchDebounceMs = Flags.long(
        "config.watch.debounce_ms",
        40,
        "How long `FileConfigStore.watch` lets change events settle before re-reading the " +
            "file. A save is rarely one event: an atomic rewrite — which is what this store's " +
            "own `set` performs, and what most editors do — arrives as a create, a modify and " +
            "a delete of the temporary file followed by a create of the target, and an " +
            "in-place write can be seen with the file truncated. This window is what makes the " +
            "read land on a finished file rather than a half-written one. It was a hard-coded " +
            "40ms; it is here because the right value is a property of whatever writes the " +
            "file, which is not this program. Raising it delays every flag flip by that much " +
            "and widens the gap in which a reader sees the old values; lowering it — 0 " +
            "re-reads on the first event — risks a parse of a partial file, which costs " +
            "nothing worse than a reported error and a reload on the next event, because a " +
            "file that will not parse keeps the last good snapshot. Read from the live " +
            "snapshot on every event, so changing it applies to the watcher already running.",
        requires = Flags.atLeast(0L),
    )

    val watchMissingFile = Flags.enum(
        "config.watch.missing_file",
        MissingFile.KEEP,
        "What a running watch does when the file it is watching stops existing. KEEP holds " +
            "the values last read out of it and reports the disappearance, which is the rule a " +
            "malformed file already gets and for the same reason — against a live desktop, " +
            "reverting every flag at once is the most expensive thing a reload can do, and it " +
            "is what the gap in a delete-then-write save would otherwise cause silently. " +
            "DEFAULTS reloads as though the file were empty, so removing it resets a running " +
            "process without a restart. This governs the *watch* only: a store built when the " +
            "file does not exist reads defaults under both values, because there are no last " +
            "values to keep.",
    )

    val watchOverflow = Flags.enum(
        "config.watch.overflow",
        WatchOverflow.REREAD,
        "What a running watch does when the operating system tells it events were lost. An " +
            "overflow arrives with no path attached — it is a statement about the queue, not " +
            "about a file — so the name filter that decides every other event cannot decide " +
            "this one, and dropping it made the watch go quiet at exactly the moment it had " +
            "most reason to read. REREAD treats it as a change: a read is total, so one " +
            "re-read repairs any number of dropped events, and the worst it costs is a reload " +
            "that finds nothing different. REPORT keeps the last values and says so through " +
            "the same channel an unreadable file uses, for a deployment where the snapshot " +
            "must only move on a change somebody can point at. An overflow needs no " +
            "registration to be delivered, so this applies to every watch.",
    )

    val lockRequired = Flags.boolean(
        "config.store.lock_required",
        false,
        "Whether `set` and `unset` refuse to write when they cannot take the cross-process " +
            "lock beside the config file. Off writes anyway and reports the degradation, " +
            "because refusing would make the config unwritable on a filesystem that will not " +
            "lock (an NFS home without lockd) — at the cost that a concurrent `set` can be " +
            "lost, which is the whole defect the lock exists to close. On refuses instead, " +
            "for a deployment that would rather have no write than a silently lost one. " +
            "Either way the write is atomic by rename over a per-process staging file, so an " +
            "unlocked write can lose an update but can never tear the file.",
    )

    val filePermissions = Flags.enum(
        "config.store.permissions",
        FilePermissions.OWNER_ONLY,
        "What permissions the config file, its lock file and its staging file are created " +
            "with. OWNER_ONLY writes 0600 (0700 on directories this store creates) as a " +
            "creation attribute rather than a chmod afterwards, so there is no window in " +
            "which the file exists and is not yet private. UMASK is the behaviour before " +
            "#102 — whatever the process umask leaves, which under the usual 022 is a " +
            "world-readable 0644 — and is kept for a deployment that deliberately widens the " +
            "umask or sets a default ACL so a group or a backup agent can read the state " +
            "directory. Existing files are not touched: this governs creation, and a rename " +
            "over an old 0644 file replaces its mode along with its contents. The registry " +
            "has the same switch over its own files as registry.store.permissions; a " +
            "deployment that wants the old behaviour has to set both.",
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
