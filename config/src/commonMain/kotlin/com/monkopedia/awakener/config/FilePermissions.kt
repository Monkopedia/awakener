package com.monkopedia.awakener.config

/**
 * What permissions awakener puts on a file or directory it creates.
 *
 * Declared here rather than in either store because both stores need it and they must not
 * disagree: `:config` and `:registry` write into directories a user reasonably assumes are one
 * thing — the config file, the bindings file and the residue sit two commands apart in the same
 * mental model — and a rule that held for one of them would read as holding for both.
 *
 * The choice this enum offers is between *stating* the permissions and *inheriting* them, which
 * is the actual defect behind #102. Nothing in either store ever passed a permission, so every
 * file and directory took the process umask; under the default `022` that is `0644` on the
 * bindings file and on every residue file. What kept that from mattering was `/home/jmonk` being
 * `0700` — a property of directories the stores neither create nor check, and one that stops
 * holding the moment `registry.residue.dir` is pointed somewhere else, which is the entire
 * purpose of that flag.
 */
enum class FilePermissions {
    /**
     * `0600` on files, `0700` on directories, applied at creation as a file attribute rather
     * than by a `chmod` afterwards — the window between "exists" and "is private" is exactly
     * the interval another process would need.
     *
     * The default, because it is the behaviour that would have been hard-coded had anyone
     * thought about it: the residue is the accumulated model of the user, which is the most
     * sensitive thing awakener will ever hold, and no reader of it is intended other than the
     * user and their agents.
     */
    OWNER_ONLY,

    /**
     * Whatever the process umask leaves — the behaviour before #102.
     *
     * Kept because there is one real thing it is for: a deployment that wants a group or a
     * backup agent to read the state directory sets its umask (or a default ACL) and expects
     * files to land under it, and [OWNER_ONLY] would silently defeat that. It is a choice about
     * who may read the user's model, so it is a flag rather than an assumption either way.
     */
    UMASK,
}
