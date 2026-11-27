package com.magpie.usage

import java.security.SecureRandom

/** JVM-only navigation/capture state shared by the WebView callbacks. */
internal data class PanelNavigation(
    val origin: String,
    val generation: Long,
    val token: String,
)

/**
 * Monotonic generations and unique tokens make callbacks from an old document
 * fail closed after any address or address-book navigation.
 */
internal class NavigationState {
    var generation: Long = 0L
        private set

    fun begin(origin: String): PanelNavigation {
        generation += 1
        return PanelNavigation(origin = origin, generation = generation, token = newToken())
    }

    fun invalidate() {
        generation += 1
    }

    fun isCurrent(navigation: PanelNavigation): Boolean =
        navigation.generation == generation

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val TOKEN_BYTES = 32
        val random = SecureRandom()
    }
}
