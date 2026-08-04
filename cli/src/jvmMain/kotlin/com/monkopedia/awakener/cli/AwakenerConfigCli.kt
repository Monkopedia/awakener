package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.ConfigCli
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
    ): Int = ConfigCli.run(args, ConfigCli.bootstrap(path, environment, out), out)
}

fun main(args: Array<String>) {
    exitProcess(AwakenerConfigCli.run(args, ConfigCli.defaultPath(), ::println))
}
