package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.FilePermissions
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

/**
 * What a forget does with the residue the forgotten agent accumulated.
 *
 * Residue is keyed on the [SurfaceKey], not on the agent, so a forget alone changes the
 * identity and leaves the written-down model exactly where it was — the freshly minted
 * Lifeless is handed the same residue path the forgotten one was writing into. That is right
 * for one of the two reasons anyone reaches for `forget` and wrong for the other, which is why
 * it is a flag: "this surface is bound to the wrong agent" wants the model to follow the
 * surface, and "this agent has the wrong model of me" wants it out of the way (#60).
 */
enum class ForgetResidue {
    /**
     * Leave it. The rebinding case, and the only value that cannot lose anything — a wrong
     * `forget` costs an agent id rather than six weeks of accumulated model.
     */
    KEEP,

    /**
     * Rename it aside, so the fresh Lifeless starts empty and the old model is still readable.
     *
     * This is what makes `forget` a real repair for "the agent has the wrong idea about me":
     * the memory model's claim is that the durable layer is inspectable when the agent gets you
     * wrong, and inspecting it is not much use if the only lever leaves the wrongness in place.
     */
    ARCHIVE,

    /**
     * Remove it. **Irreversible, and there is no undo above this layer** — nothing else in
     * awakener copies residue anywhere. For "start this surface over" reach for [ARCHIVE]
     * first; this value is for residue that should not go on existing, which is a real thing
     * to want of a file holding a model of its user, and is the one reason it is built.
     */
    DELETE,
}

/**
 * What preparing residue does when the directory it is about to write into can be written by
 * users other than this one.
 *
 * *Which* directory is asked about is [ResidueExposureScope]'s question, and the two answers have
 * different cadences — see that enum, which is where the once-per-deployment shape of the default
 * is written down. Either way the hazard is the same: in a directory another local user can write,
 * they can create the residue path first — as their own directory, or as a symlink pointing at
 * something of theirs — and awakener would then write the user's model into it. The sticky bit
 * does not close that: `/tmp` being `1777` stops another user *removing* an entry that is already
 * there, and does nothing about one that is not there yet.
 *
 * Only the write permission is examined. A directory that is merely readable by others leaks the
 * *names* of surfaces, which are app ids, while the residue files inside it are `0600` — worth
 * less than the noise it would generate on a machine with a `0755` home.
 */
enum class ResidueExposure {
    /**
     * Prepare it anyway, and say so. The default, and the same call
     * [RegistryFlags.lockRequired] makes for the same reason: this runs on the hotkey path, a
     * refusal there takes the key press down, and awakener does not own the directory the user
     * pointed it at. Naming it is what the alternative — carrying on in silence, which is
     * exactly what #102 describes — cannot do.
     */
    REPORT,

    /**
     * Refuse to prepare it, raising rather than writing. For a deployment where residue must
     * never be written where another user could have got there first, and which would rather
     * lose the hotkey than find that out later.
     */
    REFUSE,

    /**
     * Say nothing. For a shared machine where this is understood and the warning is noise.
     */
    ALLOW,
}

/**
 * Which directory [RegistryFlags.residueExposure] asks about.
 *
 * The two differ in **cadence**, and that is the whole of the choice. A residue directory awakener
 * creates is created `0700` under [RegistryFlags.filePermissions], so once it exists it is the
 * deepest existing directory on the way to itself and it is private — which means
 * [DEEPEST_EXISTING] answers "exposed" on the first `prepareResidue` of a deployment and "private"
 * on every one after it, for as long as that directory survives. **That is not a suppression and
 * not a latch**: nothing is remembered between presses, the filesystem underneath the question
 * changed, and the answer honestly changed with it. [FileBindingStore.residueExposureCheck] names
 * the directory each answer was about, which is how a reader tells the two apart.
 */
enum class ResidueExposureScope {
    /**
     * The nearest directory that already exists on the way to the residue directory.
     *
     * The default, because it is the scope on which the warning's sentence is *true*: everything
     * below the deepest existing directory is about to be created by this call at `0700`, so the
     * only component anybody else can still get to first is the next one down. Walking further up
     * would answer a different question — whether an existing directory could be *swapped* — and
     * would answer it badly, since `/tmp` is `1777` on every Linux system and a chain walk warns
     * about every path under it, including ones nobody can reach because the directory below is
     * `0700`.
     *
     * **Its consequence is stated rather than discovered**: with `registry.residue.dir` pointed
     * somewhere shared, the warning appears on the first press and never again, because by the
     * second press awakener owns the directory it was warning about and the race it named is over.
     * Right on the narrow question, and easy to miss in a stream of startup output — which is what
     * [PARENT] is for.
     */
    DEEPEST_EXISTING,

    /**
     * The directory the residue directory sits in, whether or not the residue directory exists.
     *
     * Asks a standing question instead of a race one — "is awakener's residue directory kept
     * somewhere other local users can write" — so it keeps answering the same way on every press
     * and the condition stays observable for as long as it holds. For a deployment that would
     * rather see the line on every hotkey press than have one chance at it.
     *
     * The cost is the mirror of the benefit: on a sticky `/tmp` the creation race really is over
     * once awakener owns the entry, so this keeps reporting a directory the user may have decided
     * to accept. The warning text says which of the two it is talking about.
     *
     * If the parent does not exist either, this falls back to the nearest existing directory above
     * it — the same walk [DEEPEST_EXISTING] does, and the same answer, since before the first
     * press the two scopes are looking at the same place anyway.
     */
    PARENT,
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

    val forgetResidue = Flags.enum(
        "registry.binding.forget_residue",
        ForgetResidue.KEEP,
        "What a forget does with the residue the forgotten agent accumulated. Residue is keyed " +
            "on the surface rather than on the agent, so KEEP — today's behaviour, and the " +
            "default — hands the freshly minted Lifeless the same written-down model the " +
            "forgotten one had, which is right when the complaint is 'wrong agent' and wrong " +
            "when it is 'wrong model of me'. ARCHIVE renames it to <slug>.<timestamp> beside " +
            "itself so the new agent starts empty and the old model stays readable. DELETE " +
            "removes it, which nothing undoes. Only a forget that actually dropped a binding " +
            "disposes of anything, and a disposal that fails is reported rather than folded " +
            "into the forget's own success.",
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

    val commandTimeoutMs = Flags.long(
        "registry.agent.command_timeout_ms",
        10_000,
        "How long a spanreed subprocess gets before it is killed and the run reported as timed " +
            "out. This is the hotkey's budget: id_source=SPANREED shells out on every first " +
            "bind and `attach` awaits it, so a bus that has wedged stalls the key press for " +
            "this long. Lower it on a desktop where a slow answer should fail fast, raise it " +
            "for a bus that is genuinely slow under load. The child's own stderr survives the " +
            "kill either way, because it is the only account of why it was slow. Read once per " +
            "run, so a flip applies to the next command rather than to one already in flight.",
        Flags.atLeast(1L),
    )

    val drainGraceMs = Flags.long(
        "registry.agent.drain_grace_ms",
        1_000,
        "How long a finished child's pipes are still read before the runner keeps what arrived " +
            "and reports the read as short. A child's exit does not close a pipe a grandchild " +
            "inherited — `spanreed` leaving anything in the background is enough — so without a " +
            "bound the hotkey waits on a process it never started; with one, an answer still in " +
            "flight is refused rather than believed. Spent per pipe and only when the drain has " +
            "not already finished, so a well-behaved child costs none of it. Raising it makes " +
            "awakener wait longer for a slow answer instead of refusing it; lowering it refuses " +
            "sooner. 0 keeps only what is already buffered when the child exits. Either way a " +
            "truncated answer is reported as truncated: what this number moves is the line " +
            "between waiting and refusing, never between refusing and silently accepting.",
        Flags.atLeast(0L),
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

    val filePermissions = Flags.enum(
        "registry.store.permissions",
        FilePermissions.OWNER_ONLY,
        "What permissions this module creates files and directories with: the bindings file, " +
            "its lock and staging files, the residue directory and each surface's residue. " +
            "OWNER_ONLY writes 0600 on files and 0700 on directories, applied as a creation " +
            "attribute rather than a chmod afterwards so there is no window in which the file " +
            "exists and is not yet private. UMASK is the behaviour before #102 — whatever the " +
            "process umask leaves, which under the usual 022 is a world-readable 0644 on " +
            "every residue file — and is kept for a deployment that widens the umask or sets " +
            "a default ACL on purpose so that a group or a backup agent can read the state " +
            "directory. Only creation is governed: a directory that already exists is left as " +
            "it is, which is registry.residue.exposed_dir's question rather than this one. " +
            "`:config` has the same switch over its own files as config.store.permissions.",
    )

    val residueExposure = Flags.enum(
        "registry.residue.exposed_dir",
        ResidueExposure.REPORT,
        "What preparing residue does when the nearest existing directory above it is writable " +
            "by other users — registry.residue.dir pointed at /tmp is the case this exists " +
            "for. There another local user can create the residue path before awakener does, " +
            "as their own directory or as a symlink onto something of theirs, and the sticky " +
            "bit does not prevent it: 1777 stops an entry being removed, not one being " +
            "created. REPORT prepares it and warns, which is the default because this is the " +
            "hotkey path and a refusal there costs the key press, and because awakener does " +
            "not own the directory it was pointed at. REFUSE raises instead, for a deployment " +
            "that would rather lose the hotkey than write a model somewhere another user " +
            "reached first. ALLOW says nothing. Only write permission is examined: a merely " +
            "readable directory leaks surface names, and the residue files in it are 0600.",
    )

    val residueExposureScope = Flags.enum(
        "registry.residue.exposure_scope",
        ResidueExposureScope.DEEPEST_EXISTING,
        "Which directory registry.residue.exposed_dir examines. DEEPEST_EXISTING is the nearest " +
            "directory that already exists on the way to the residue directory: the scope on " +
            "which the warning's sentence is literally true, and the default. It is also why " +
            "that warning fires once per deployment and not again — awakener creates the " +
            "residue directory 0700 on the first press, which makes it the deepest existing " +
            "directory from then on, so the race it named is genuinely over. PARENT examines " +
            "the directory the residue directory sits in instead, exists or not, which is a " +
            "standing condition rather than a race and so stays reported on every press. " +
            "Neither value suppresses anything: the check runs in full every time and " +
            "`residueExposureCheck` names the directory it answered about.",
    )

    val residueLayout = Flags.enum(
        "registry.residue.layout",
        ResidueLayout.PER_KEY_FILE,
        "One markdown file per surface, or one directory per surface. The directory form " +
            "leaves room for distillation to emit more than a single artifact.",
    )
}
