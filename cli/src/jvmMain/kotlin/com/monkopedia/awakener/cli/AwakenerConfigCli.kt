package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.ConfigCli
import com.monkopedia.awakener.config.ConfigFlags
import com.monkopedia.awakener.config.FileConfigStore
import com.monkopedia.awakener.config.FlagDiscovery
import com.monkopedia.awakener.config.Flags
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `awakener-config` — the entry point for inspecting and changing flags.
 *
 * It lives in `:cli` because this is the one module that depends on every other module, which
 * is what puts all the flag-declaring classes on a single classpath for discovery to find.
 * Nothing else here belongs to the CLI: the commands themselves are `:config`'s.
 */
object AwakenerConfigCli {
    fun run(
        args: Array<String>,
        path: Path,
        out: (String) -> Unit,
        environment: Map<String, String> = System.getenv(),
    ): Int {
        // Bootstrap: the flags that decide how discovery runs must be registered before the
        // store is built, or their own environment overrides would not be picked up.
        Flags.requireLoaded(ConfigFlags)
        val store = FileConfigStore(path, environment)
        val config = store.config.value
        val report = FlagDiscovery.discover(
            config[ConfigFlags.discovery],
            config[ConfigFlags.declarations],
        )
        report.problems.forEach { out("warning: flag discovery: $it") }
        // That first snapshot was parsed against a registry holding only the bootstrap flags,
        // so it called every other module's key unknown. Now that they exist, read it again.
        store.reload()
        return ConfigCli.run(args, store, out)
    }
}

fun main(args: Array<String>) {
    exitProcess(AwakenerConfigCli.run(args, ConfigCli.defaultPath(), ::println))
}
