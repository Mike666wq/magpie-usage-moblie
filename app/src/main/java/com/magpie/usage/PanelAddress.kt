package com.magpie.usage

import android.content.Context
import android.content.SharedPreferences
import java.net.IDN
import java.net.Inet6Address
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale

private const val PREFERENCES_NAME = "magpie_usage"
private const val PREFERENCE_ORIGIN = "panel_origin"

/**
 * A navigation target. Only [origin] belongs in persistent storage.
 * [initialUrl] is short-lived. When the user pastes a full sign-in link it
 * contains k only for the first request; [cleanUrl] is its key-free form.
 */
internal data class PanelTarget(
    val origin: String,
    val initialUrl: String,
    val cleanUrl: String,
    val redirectAfterLoad: Boolean,
)

internal object PanelAddress {
    private const val SCHEME_SEPARATOR = "://"
    private const val HOST_SEPARATOR = "@"
    private const val PATH_SEPARATOR = '/'
    private const val QUERY_SEPARATOR = '?'
    private const val KEY_PARAMETER = "k"
    private const val DEFAULT_VIEW = "usage"

    fun parse(rawInput: String): PanelTarget {
        val input = rawInput.trim()
        require(input.isNotEmpty()) { "Address must not be empty." }

        // A bare scheme is not a host, and treating "javascript:..." as a
        // hostname would hide an unsupported scheme behind the HTTP fallback.
        val colon = input.indexOf(':')
        val slash = input.indexOf('/')
        val schemeCandidate =
            if (colon > 0) input.substring(0, colon).lowercase(Locale.ROOT) else ""
        val afterColon = if (colon >= 0) input.substring(colon + 1) else ""
        val possiblePort = afterColon.substringBefore('/').substringBefore('?')
            .substringBefore('#')
        val looksLikeScheme = colon > 0 && (slash < 0 || colon < slash) &&
            schemeCandidate.matches(Regex("[A-Za-z][A-Za-z0-9+.-]*")) &&
            !(possiblePort.isNotEmpty() && possiblePort.all { it.isDigit() })
        if (looksLikeScheme && !input.startsWith("http:", ignoreCase = true) &&
            !input.startsWith("https:", ignoreCase = true)
        ) {
            throw IllegalArgumentException("Only HTTP and HTTPS are supported.")
        }

        val fullInput = if (input.startsWith("http:", ignoreCase = true) ||
            input.startsWith("https:", ignoreCase = true)
        ) {
            input
        } else {
            "http://$input"
        }
        val uri = try {
            URI(fullInput)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("Invalid absolute URL.")
        }
        if (fullInput.contains('[') || fullInput.contains(']')) {
            require(uri.host != null) { "Invalid IPv6 host." }
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("An absolute URL is required.")

        // RFC 3986 requires "//" before an authority. A normal URI parser
        // treats this as an optional detail, so enforce complete URL syntax.
        if (!fullInput.regionMatches(0, "$scheme://", 0, 0, ignoreCase = true)) {
            throw IllegalArgumentException("An absolute URL is required.")
        }

        val authority = fullInput.substringAfter(SCHEME_SEPARATOR)
            .substringBefore(PATH_SEPARATOR)
            .substringBefore(QUERY_SEPARATOR)
            .substringBefore('#')
        if (authority.isEmpty() || authority.contains(HOST_SEPARATOR)) {
            throw IllegalArgumentException("A host without user information is required.")
        }
        validatePortSyntax(authority)

        // java.net.URI leaves the host null for some syntactically complete
        // bracketed IPv6 authorities. Parse those from the authority itself.
        val bracketedHost = if (authority.startsWith('[')) {
            authority.substringAfter('[').substringBefore(']').takeIf { it.isNotBlank() }
        } else {
            null
        }
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: bracketedHost
            ?: throw IllegalArgumentException("A host is required.")
        val port = if (uri.port >= 0) {
            uri.port
        } else if (authority.endsWith(':')) {
            throw IllegalArgumentException("A port number is required after ':'.")
        } else {
            -1
        }
        if (port != -1 && port !in 1..65535) {
            throw IllegalArgumentException("The port must be between 1 and 65535.")
        }

        val defaultPort = if (scheme == "https") 443 else 80
        val portSuffix = if (port == -1 || port == defaultPort) "" else ":$port"
        val normalizedHost = normalizeHost(host, authority)
        val origin = "$scheme://$normalizedHost$portSuffix"
        val cleanInitialUrl = cleanFirstLoadUrl(uri, scheme, normalizedHost, portSuffix)
        val key = parameterValues(uri, KEY_PARAMETER).firstOrNull { it.isNotEmpty() }
        val initialUrl = if (key != null) withKey(cleanInitialUrl, key) else cleanInitialUrl

        return PanelTarget(
            origin = origin,
            initialUrl = initialUrl,
            cleanUrl = cleanInitialUrl,
            redirectAfterLoad = key != null,
        )
    }

    fun storedOrigin(preferences: SharedPreferences): String? {
        return preferences.getString(PREFERENCE_ORIGIN, null)?.let { stored ->
            try {
                parse(stored).origin
            } catch (_: IllegalArgumentException) {
                // Do not echo an invalid stored value; it may predate the
                // origin-only storage contract.
                null
            }
        }
    }

    fun saveOrigin(context: Context, origin: String) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFERENCE_ORIGIN, origin)
            .apply()
    }

    fun usageUrl(origin: String): String = "$origin/?view=usage"

    private fun validatePortSyntax(authority: String) {
        if (authority.startsWith('[')) {
            val closingBracket = authority.indexOf(']')
            if (closingBracket < 0) {
                throw IllegalArgumentException("Invalid IPv6 host.")
            }
            val suffix = authority.substring(closingBracket + 1)
            if (suffix.isNotEmpty() &&
                (!suffix.startsWith(':') || !suffix.substring(1).all { it.isDigit() })
            ) {
                throw IllegalArgumentException("Invalid port.")
            }
        } else if (authority.contains(':')) {
            val portText = authority.substringAfterLast(':')
            if (!portText.all { it.isDigit() }) {
                throw IllegalArgumentException("Invalid port.")
            }
        }
    }

    private fun parameterValues(uri: URI, name: String): List<String> {
        val rawQuery = uri.rawQuery ?: return emptyList()
        return rawQuery.split('&').mapNotNull { part ->
            if (part.isEmpty()) return@mapNotNull null
            val rawName = part.substringBefore('=')
            val rawValue = if (part.contains('=')) part.substringAfter('=') else ""
            if (decode(rawName) == name) decode(rawValue) else null
        }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

    private fun withKey(cleanUrl: String, key: String): String {
        return cleanUrl + "&" + KEY_PARAMETER + "=" + URLEncoder.encode(key, "UTF-8")
    }

    /** Build a clean URL without copying or decoding the original query. */
    private fun cleanFirstLoadUrl(
        uri: URI,
        scheme: String,
        host: String,
        portSuffix: String,
    ): String {
        val base = "$scheme://$host$portSuffix"
        val path = uri.rawPath.orEmpty()
        val view = parameterValues(uri, "view").firstOrNull { value ->
            value.equals("usage", ignoreCase = true) || value.equals("routing", ignoreCase = true)
        }
        val safeView = view?.lowercase(Locale.ROOT) ?: DEFAULT_VIEW

        return buildString {
            append(base)
            append(if (path.isEmpty()) PATH_SEPARATOR else path)
            append(QUERY_SEPARATOR)
            append("view=")
            append(URLEncoder.encode(safeView, "UTF-8"))
        }
    }

    private fun normalizeHost(host: String, authority: String): String {
        val withoutBrackets = host.removePrefix("[").removeSuffix("]")
        val isIpv6 = withoutBrackets.contains(':')
        val normalized = if (isIpv6) {
            if (!authority.startsWith('[') || !authority.endsWith(']') &&
                !authority.substringAfter(']').startsWith(':')
            ) {
                throw IllegalArgumentException("IPv6 addresses must be enclosed in brackets.")
            }
            try {
                // Validate the literal, but keep the user's compressed form.
                // Inet6Address.getHostAddress() expands 2001:db8::1 into eight
                // groups, which would make equivalent origins compare unequal.
                Inet6Address.getByName(withoutBrackets)
            } catch (_: UnknownHostException) {
                throw IllegalArgumentException("Invalid IPv6 host.")
            }
            withoutBrackets.lowercase(Locale.ROOT)
        } else {
            val ascii = try {
                IDN.toASCII(withoutBrackets)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid host.")
            }.lowercase(Locale.ROOT)
            // URI decodes the host. Reject encoded delimiters so the
            // normalized origin cannot change its meaning.
            if (ascii.contains('%') || ascii.contains(PATH_SEPARATOR) ||
                ascii.contains(QUERY_SEPARATOR) || ascii.contains('#') ||
                ascii.contains(HOST_SEPARATOR)
            ) {
                throw IllegalArgumentException("Invalid host.")
            }
            ascii
        }
        if (normalized.isEmpty() || normalized.any { it.isWhitespace() }) {
            throw IllegalArgumentException("Invalid host.")
        }

        val trailingDot = if (isIpv6) "" else normalized.removeSuffix(".")
        val labelPattern = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        if (!isIpv6 && (trailingDot.isEmpty() || trailingDot.length > 253 ||
                trailingDot.split('.').any { !labelPattern.matches(it) })
        ) {
            throw IllegalArgumentException("Invalid host.")
        }

        return if (isIpv6) "[$normalized]" else trailingDot
    }
}
