package com.magpie.usage

/** The single external-scheme policy used by both redirects and new windows. */
internal enum class NavigationAction {
    KEEP_IN_WEB_VIEW,
    OPEN_EXTERNALLY,
}

internal object NavigationPolicy {
    fun isInternalBlank(rawUrl: String): Boolean {
        val normalized = rawUrl.trim()
        if (normalized.isEmpty()) return false
        val uri = try {
            java.net.URI(normalized)
        } catch (_: java.net.URISyntaxException) {
            return false
        }
        val target = uri.rawSchemeSpecificPart
        return uri.scheme.equals("about", ignoreCase = true) &&
            (target.isEmpty() || target.equals("blank", ignoreCase = true)) &&
            uri.rawQuery == null && uri.rawFragment == null
    }

    fun forUrl(rawUrl: String): NavigationAction {
        if (isInternalBlank(rawUrl)) {
            return NavigationAction.KEEP_IN_WEB_VIEW
        }
        val scheme = rawUrl.substringBefore(':').lowercase()
        val hasScheme = rawUrl.startsWith("$scheme:", ignoreCase = true)
        return if (hasScheme && (scheme == "http" || scheme == "https")) {
            NavigationAction.KEEP_IN_WEB_VIEW
        } else {
            NavigationAction.OPEN_EXTERNALLY
        }
    }

    /** Returns false when the destination must stay on the configured panel origin. */
    fun isPanelTopLevelUrl(rawUrl: String, panelOrigin: String): Boolean {
        val target = try {
            PanelAddress.parse(rawUrl)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return target.origin == panelOrigin
    }
}
