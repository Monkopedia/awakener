package com.monkopedia.awakener.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [ConfigStore] held entirely in memory.
 *
 * Used by tests to pin a flag without touching the user's real config, and by any run that
 * wants explicit configuration rather than whatever is on disk.
 */
class InMemoryConfigStore(initial: Config = Config.EMPTY) : ConfigStore {
    private val state = MutableStateFlow(initial)

    override val config: StateFlow<Config> = state.asStateFlow()

    override suspend fun set(key: String, raw: String) {
        val flag = requireNotNull(Flags.byKey(key)) { "unknown flag '$key'" }
        state.value = state.value.with(key, flag.parseRaw(raw))
    }

    override suspend fun unset(key: String) {
        require(Flags.byKey(key) != null) { "unknown flag '$key'" }
        state.value = state.value.without(key)
    }

    /** Non-suspending convenience for test setup. */
    fun <T> put(flag: Flag<T>, value: T): InMemoryConfigStore = apply {
        state.value = state.value.with(flag.key, flag.encode(value))
    }
}
