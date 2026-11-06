package com.monkopedia.awakener.config

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * The XDG base directories, read the way the spec says to read them (#191).
 *
 * The spec is explicit that a variable which is set but empty is treated as unset, and that a
 * relative one is invalid and ignored. A bare `environment[name] ?: default` gets both wrong, and
 * gets them wrong silently: `XDG_STATE_HOME=""` — an `export` of something unset in a profile, a
 * systemd `Environment=` line — moved the bindings file under whatever working directory the
 * compositor exec'd the hotkey from, so every agent appeared to have lost its memory and nothing
 * said why. Every module resolves these through here so there is one place that rule lives.
 */
object Xdg {
    fun configHome(environment: Map<String, String>): Path =
        base(environment["XDG_CONFIG_HOME"]) ?: home(".config")

    fun stateHome(environment: Map<String, String>): Path =
        base(environment["XDG_STATE_HOME"]) ?: home(".local/state")

    /**
     * [value] as a base directory, or null when the spec says to ignore it: unset, empty, or
     * relative. For a variable with no default of its own, such as `XDG_RUNTIME_DIR`.
     */
    fun base(value: String?): Path? = value?.let(::Path)?.takeIf { it.isAbsolute }

    private fun home(relative: String): Path = Path(System.getProperty("user.home"), relative)
}
