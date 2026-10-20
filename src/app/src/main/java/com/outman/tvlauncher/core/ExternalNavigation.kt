package com.outman.tvlauncher.core

/** Resolve/create/start failures all fall through; a successful launch stops the chain. */
object ExternalNavigation {
    fun launchFirst(attempts: List<() -> Unit>): Boolean {
        for (attempt in attempts) {
            try {
                attempt()
                return true
            } catch (_: Exception) {
                // Do not log intents: they may contain private URI parameters.
            }
        }
        return false
    }
}
