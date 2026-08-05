package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Flags

/** What makes two windows "the same surface" for the purpose of a durable binding. */
enum class WindowIdentity {
    /** One Lifeless per application. Stable across restarts, which is the point of persisting. */
    APP_ID,

    /**
     * One Lifeless per window title. Splits a multi-window app (an editor per project, a
     * terminal per host) but is only as stable as the app's titles — a title that carries a
     * dirty marker or a line number will mint a new agent every time it changes.
     */
    APP_ID_AND_TITLE,

    /**
     * One Lifeless per running window instance. Deliberately *not* stable across restarts: pids
     * are recycled, so this is the "give me a fresh agent per launch" setting, useful when the
     * accumulated model would be noise.
     */
    APP_ID_AND_PID,
}

/** Where an agent id comes from when a surface is first bound. */
enum class AgentIdSource {
    /**
     * Ask the `spanreed` CLI. Authoritative by construction: the id comes from the same code
     * the Lifeless session will run when it starts up with this `SPANREED_AGENT_NAME`, so the
     * two cannot disagree. Costs one subprocess per mint.
     */
    SPANREED,

    /**
     * Apply spanreed's documented override rule (`agent-<name>`) locally. No subprocess, and it
     * works with spanreed absent — but it pins a documented behaviour rather than reading it,
     * so it drifts silently if spanreed ever changes the rule.
     */
    DERIVED,
}

/**
 * What a bind does when `registry.agent.id_source=SPANREED` and spanreed cannot be run at all.
 *
 * Scoped to *cannot be run* — a missing executable, or a typo in
 * [RegistryFlags.spanreedCommand] — and to nothing else. A spanreed that ran and exited non-zero
 * is a bus that is present with something else wrong; deriving past that would hide it. Neither
 * value covers a run whose output came back short, because that is a spanreed which answered and
 * the answer is the thing in doubt.
 */
enum class UnreachableIdSource {
    /**
     * Refuse to mint. This is what awakener has always done, and it is the value that never
     * writes a durable binding under an id spanreed did not issue — the binding outlives the
     * outage, so an id invented during one is an id the surface keeps.
     */
    FAIL,

    /**
     * Apply [AgentIdSource.DERIVED]'s rule for this mint, and say so on stderr.
     *
     * The bind then works with the bus down, which is a real thing to want from a hotkey. The
     * cost is that the id is a *pin* of spanreed's documented rule rather than a reading of it,
     * so a surface bound during an outage disagrees with spanreed forever if that rule ever
     * changes. Reporting it is not decoration: substituting one id source for another in
     * silence is precisely the quiet behaviour change #62 was about.
     *
     * **This gets the mint through, not the whole press.** Under the default
     * `invoke.when_animated=REUSE` the hotkey also asks the bus whether this Lifeless is
     * already animated, and that question raises on an unreadable bus by design — a false
     * "nobody is animated" stands a second panel beside one already there. So a press that has
     * to work with spanreed absent wants `invoke.when_animated=SECOND_DOCK` as well, which is
     * the flag that says "do not ask". Verified against sway 1.12: the pair stands the dock up
     * and exits 0 with no spanreed on `PATH`; this value alone mints, warns, and then stops at
     * the bus.
     */
    DERIVE,
}

/** What a window that reports no `app_id` — an xwayland window, usually — is keyed on. */
enum class MissingAppId {
    /**
     * The window title. Keeps such windows separable, but a title is not durable: it churns with
     * the document, the tab, or a dirty marker, and each new title orphans the residue the old
     * one accumulated.
     */
    TITLE,

    /**
     * A single fixed key. Every window with no `app_id` shares one agent — coarse, but the
     * residue under it stops being abandoned every time a title changes.
     */
    UNIDENTIFIED,
}

/** What binding an already-bound surface does. */
enum class RebindPolicy {
    /** Keep the existing agent. Stability across restarts is the entire point of this layer. */
    KEEP,

    /** Take the newly supplied agent, abandoning the old one's accumulated residue. */
    REPLACE,
}

/**
 * How often a durable store re-reads the file underneath it.
 *
 * The bindings file has more than one writer whether or not the code admits it: a long-lived
 * holder plus an `awakener-registry forget` beside it is the normal shape, not an exotic one.
 */
enum class StoreReload {
    /**
     * Never. The snapshot taken at construction is treated as the whole truth, so every write
     * puts that snapshot back — which silently undoes anything another process did in between,
     * including a `forget`. Kept because it is the only setting with no per-operation file
     * read at all, not because it is safe with a second writer around.
     */
    NEVER,

    /**
     * Re-read under the file lock immediately before each write, so a write only ever
     * contributes the entry it means to change. Durable state is then correct, but a holder
     * can still *answer* with a binding that has since been forgotten.
     */
    BEFORE_WRITE,

    /**
     * Also re-read before each resolve. Costs a small file read on the hotkey path and buys
     * the property the repair path actually promises: once `forget` returns, nothing resolves
     * to the old agent any more.
     */
    BEFORE_READ,
    ;

    internal val onWrite: Boolean get() = this != NEVER

    internal val onRead: Boolean get() = this == BEFORE_READ
}

/** What a holder's bind does with a binding that was forgotten underneath it. */
enum class ForgetConflict {
    /**
     * The forget stands: the surface is unbound, so binding it again mints a fresh Lifeless.
     * `forget` is an explicit instruction from the user and this is what it advertises.
     */
    FORGET_WINS,

    /**
     * The holder re-establishes the identity it is carrying. A Lifeless that is up and
     * mid-session keeps its surface, which is the kinder answer to a forget that was a
     * mistake — at the cost that the repair path stops repairing anything still in use.
     */
    HOLDER_WINS,
}

/** How a surface's durable residue is laid out on disk. */
enum class ResidueLayout {
    /** One file per surface, `<slug>.md`. Simplest thing that stays readable by hand. */
    PER_KEY_FILE,

    /** One directory per surface, so distillation can write several artifacts side by side. */
    PER_KEY_DIR,
}

/**
 * Runtime switches for the durable binding layer.
 *
 * Each of these is a place where two behaviours are defensible and the answer depends on how
 * Jason actually works, not on a fact about the system — so both are built and the default is
 * whichever would otherwise have been hard-coded.
 */
object RegistryFlags {
    val storePath = Flags.string(
        "registry.store.path",
        "",
        "Path to the bindings file. Empty means \$XDG_STATE_HOME/awakener/bindings.json.",
    )

    val storeReload = Flags.enum(
        "registry.store.reload",
        StoreReload.BEFORE_READ,
        "How often a store re-reads its bindings file. The file has a second writer whenever " +
            "`awakener-registry forget` runs, so re-reading is what keeps a forget from being " +
            "undone; NEVER is the old single-writer store, cheapest and unsafe beside a CLI. " +
            "BEFORE_WRITE keeps the file correct but not the answers: a holder goes on " +
            "resolving to an agent that was forgotten until something makes it write.",
    )

    val lockRequired = Flags.boolean(
        "registry.store.lock_required",
        false,
        "Whether a store refuses to write when it cannot take the cross-process lock on its " +
            "bindings file. Off keeps the hotkey working on a filesystem that will not lock (an " +
            "NFS home without lockd), reporting the degradation rather than hiding it, at the " +
            "cost that a concurrent `awakener-registry forget` can be lost. On refuses to bind " +
            "at all rather than write without exclusion.",
    )

    val forgetConflict = Flags.enum(
        "registry.binding.forget_conflict",
        ForgetConflict.FORGET_WINS,
        "What a holder's bind does with a binding forgotten underneath it: honour the forget " +
            "and mint a fresh Lifeless, or re-establish the identity the holder is carrying so " +
            "a live agent keeps its surface.",
    )

    val windowIdentity = Flags.enum(
        "registry.key.window_identity",
        WindowIdentity.APP_ID,
        "What makes two windows the same surface. Per-app is stable across restarts; per-title " +
            "splits a multi-window app but inherits that app's title churn.",
    )

    val missingAppId = Flags.enum(
        "registry.key.missing_app_id",
        MissingAppId.TITLE,
        "What a window reporting no app_id is keyed on. The title keeps such windows separable " +
            "but churns, orphaning residue on every retitle; the fixed key is stable but puts " +
            "every unidentified window on one agent.",
    )

    val agentNamePrefix = Flags.string(
        "registry.agent.name_prefix",
        "lifeless-",
        "Prefix for the SPANREED_AGENT_NAME minted for a surface. These names show up in " +
            "`spanreed list`, so the prefix is what keeps Lifelesses distinguishable from " +
            "human-driven sessions.",
    )

    val agentIdSource = Flags.enum(
        "registry.agent.id_source",
        AgentIdSource.SPANREED,
        "Where a new agent id comes from: the spanreed CLI, or spanreed's documented rule " +
            "applied locally without a subprocess.",
    )

    val idSourceUnreachable = Flags.enum(
        "registry.agent.id_source_unreachable",
        UnreachableIdSource.FAIL,
        "What a bind does when id_source=SPANREED and the spanreed executable cannot be run at " +
            "all — spanreed not installed, or a typo in registry.agent.spanreed_command. FAIL " +
            "refuses to mint, which is the existing behaviour and the one that never writes a " +
            "durable binding under an id spanreed did not issue. DERIVE applies spanreed's " +
            "documented agent-<name> rule locally so a hotkey still works with the bus down, " +
            "and reports the substitution on stderr. Only a command that cannot run is covered: " +
            "a spanreed that ran and failed is a present bus with something else wrong, and a " +
            "run whose output came back short is an answer that cannot be trusted — both still " +
            "refuse under either value. DERIVE covers the mint, not the whole press: the " +
            "default invoke.when_animated=REUSE also asks the bus who is animated, and that " +
            "question raises on an unreadable bus by design, so pair it with SECOND_DOCK for a " +
            "hotkey that never asks.",
    )

    val spanreedCommand = Flags.string(
        "registry.agent.spanreed_command",
        "spanreed",
        "The spanreed executable. spanreed's CLI is its public contract; its registry file and " +
            "lockfile are not, so this is the only way awakener is allowed to reach the bus.",
        // Blank is not a spanreed that might exist, it is an argv whose first element is the
        // empty string — so it degrades to the default and is reported, rather than reaching
        // ProcessBuilder to come back as "could not run ''".
        Flags.nonBlank(),
    )

    val registerOnMint = Flags.boolean(
        "registry.agent.register_on_mint",
        false,
        "Mirror a freshly minted Lifeless into spanreed's registry at bind time. Off by " +
            "default because spanreed keys liveness on pid + pid_start, so an entry registered " +
            "under awakener's own pid would claim a liveness the Lifeless does not yet have.",
    )

    val rebindPolicy = Flags.enum(
        "registry.binding.rebind_policy",
        RebindPolicy.KEEP,
        "What binding an already-bound surface does. Keeping is what makes the agent survive a " +
            "restart at all; replacing abandons the residue accumulated under the old id.",
    )

    val residueDir = Flags.string(
        "registry.residue.dir",
        "",
        "Directory holding each surface's distilled residue. Empty means a `residue` " +
            "directory beside the bindings file.",
    )

    val residueLayout = Flags.enum(
        "registry.residue.layout",
        ResidueLayout.PER_KEY_FILE,
        "One markdown file per surface, or one directory per surface. The directory form " +
            "leaves room for distillation to emit more than a single artifact.",
    )
}
