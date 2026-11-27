package com.magpie.usage

import java.security.SecureRandom

/** JVM-only navigation/capture state shared by the WebView callbacks. */
internal data class PanelNavigation(
    val origin: String,
    val generation: Long,
    val token: String,
) {
    /**
     * A restored Activity can spend time between [NavigationState.restore] and
     * [NavigationState.begin]. Nothing may load or capture during that gap.
     */
    val canResume: Boolean
        get() = token.isNotBlank() && generation > 0L

    val isStale: Boolean
        get() = !canResume
}

/**
 * Monotonic generations and unique tokens make callbacks from an old document
 * fail closed after any address, invalidation or Activity recreation.
 */
internal class NavigationState {
    var generation: Long = 0L
        private set

    fun begin(origin: String): PanelNavigation {
        generation = generation.coerceAtLeast(0L) + 1L
        return PanelNavigation(
            origin = origin,
            generation = generation,
            token = newToken(),
        )
    }

    /**
     * Resumes the lightweight state without reviving the previous WebView or
     * capture token. Saved-state origins are not secrets, but full URLs are
     * deliberately never part of this API.
     */
    fun restore(savedGeneration: Long): PanelNavigation {
        generation = savedGeneration.coerceAtLeast(0L)
        return PanelNavigation(
            origin = "",
            generation = generation,
            token = "",
        )
    }

    fun bump() {
        invalidate()
    }

    fun invalidate() {
        generation = generation.coerceAtLeast(0L) + 1L
    }

    fun isCurrent(navigation: PanelNavigation): Boolean =
        navigation.generation == generation && navigation.canResume

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
