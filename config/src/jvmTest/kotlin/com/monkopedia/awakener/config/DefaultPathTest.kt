package com.monkopedia.awakener.config

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where `awakener-config` and every other entry point look for the config file (#191).
 *
 * The XDG Base Directory spec says a variable that is set but empty is treated as unset, and that
 * a relative one is invalid and ignored. Read with a bare `?:`, an exported-empty
 * `XDG_CONFIG_HOME` put the file at `/awakener/config.json` — unreadable, so every flag silently
 * read its default — and an empty `AWAKENER_CONFIG` named the working directory itself.
 */
class DefaultPathTest {
    private val home = System.getProperty("user.home")
    private val fallback = Path(home, ".config/awakener/config.json")

    @Test
    fun `absent variables fall back to the home directory`() {
        assertEquals(fallback, ConfigCli.defaultPath(emptyMap()))
    }

    @Test
    fun `an exported-empty XDG_CONFIG_HOME is unset`() {
        assertEquals(fallback, ConfigCli.defaultPath(mapOf("XDG_CONFIG_HOME" to "")))
    }

    @Test
    fun `a relative XDG_CONFIG_HOME is ignored`() {
        assertEquals(fallback, ConfigCli.defaultPath(mapOf("XDG_CONFIG_HOME" to "cfg")))
    }

    @Test
    fun `an absolute XDG_CONFIG_HOME is used`() {
        assertEquals(
            Path("/srv/cfg/awakener/config.json"),
            ConfigCli.defaultPath(mapOf("XDG_CONFIG_HOME" to "/srv/cfg")),
        )
    }

    @Test
    fun `an exported-empty AWAKENER_CONFIG is unset`() {
        assertEquals(
            fallback,
            ConfigCli.defaultPath(mapOf("AWAKENER_CONFIG" to "", "XDG_CONFIG_HOME" to "")),
        )
    }

    @Test
    fun `AWAKENER_CONFIG wins when set`() {
        assertEquals(
            Path("/etc/awakener.json"),
            ConfigCli.defaultPath(
                mapOf("AWAKENER_CONFIG" to "/etc/awakener.json", "XDG_CONFIG_HOME" to "/srv/cfg"),
            ),
        )
    }
}
