package com.magpie.usage

import android.webkit.WebView
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * Injects no code on ordinary pages. It recognizes only magpie's native 401
 * form, listens to that form's submit, and reports the key through a
 * Javascript-interface method. It does not read the DOM, other forms, or URLs.
 */
internal object LoginKeyCapture {
    private const val SIGN_IN_PATH = "/_magpie/sign-in"
    private const val INTERFACE_NAME = "MagpieDefaultKey"
    private val SCRIPT = """
        (function () {
          if (window.__magpieDefaultKeyInstalled) return;
          var form = document.querySelector('form[method="post"][action$="/_magpie/sign-in"]');
          if (!form) return;
          var input = form.querySelector('input[type="password"][name="key"]');
          var back = form.querySelector('input[type="hidden"][name="back"]');
          if (!input || !back) return;
          window.__magpieDefaultKeyInstalled = true;
          form.addEventListener('submit', function (event) {
            if (!event.isTrusted || event.defaultPrevented ||
                document.documentElement.getAttribute('data-magpie-key-captured') === '1') return;
            var key = input.value;
            if (!key || event.submitter !== form.querySelector('button[type="submit"]')) return;
            try {
              JSON.parse(window.MagpieDefaultKey.capture(JSON.stringify({
                path: window.location.pathname,
                key: key,
                back: back.value
              })));
            } catch (_) {}
          }, true);
        })();
    """.trimIndent()

    fun addJavascriptInterface(view: WebView, onCapture: (Capture) -> Unit) {
        view.addJavascriptInterface(
            object {
                @android.webkit.JavascriptInterface
                fun capture(encoded: String) {
                    val capture = try {
                        val data = JSONObject(encoded)
                        Capture(
                            path = data.getString("path"),
                            key = data.getString("key"),
                            back = data.getString("back"),
                        )
                    } catch (_: Exception) {
                        null
                    } ?: return
                    onCapture(capture)
                }
            },
            INTERFACE_NAME,
        )
    }

    fun injectIfEligible(
        view: WebView,
        url: String?,
        panelOrigin: String?,
        captureEnabled: Boolean = false,
    ): Boolean {
        if (!captureEnabled || url == null || panelOrigin == null) return false
        if (!NavigationPolicy.isPanelTopLevelUrl(url, panelOrigin)) return false
        val path = try {
            URI(url).rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        } catch (_: URISyntaxException) {
            return false
        }
        // Only the canonical root login document is inspected. Subframes,
        // assets and custom non-root deployments are intentionally not scanned.
        if (path != "/") return false
        // Pass the already-validated origin into the page. Its equality check
        // fails closed for subframes and any document that later changes origin.
        val guardedScript = """
            (function () {
              if (window.origin !== ${JSONObject.quote(panelOrigin)}) return;
              if (window.top !== window.self || window.location.pathname !== '/') return;
              try { $SCRIPT } catch (_) {}
            })();
        """.trimIndent()
        view.evaluateJavascript(guardedScript, null)
        return true
    }

    internal data class Capture(
        val path: String,
        val key: String,
        val back: String,
    )

    fun accept(capture: Capture, expectedOrigin: String?): String? {
        if (expectedOrigin == null || capture.key.isBlank()) return null
        if (!isCanonicalRootPath(capture.path)) return null
        val back = try {
            URI(capture.back)
        } catch (_: URISyntaxException) {
            return null
        }
        if (back.rawAuthority != null || back.scheme != null || back.rawFragment != null) return null
        val backPath = back.rawPath ?: return null
        val decodedPath = try {
            URLDecoder.decode(backPath.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            return null
        }
        val decodedQuery = try {
            URLDecoder.decode(back.rawQuery.orEmpty().replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!isSafeReturnPath(backPath) ||
            !isSafeQuery(decodedQuery)
        ) {
            return null
        }
        // Check each component after percent-decoding. This rejects encoded
        // slashes/backslashes that a browser could normalize into an authority
        // or the sign-in endpoint, while allowing legitimate panel query paths.
        if (listOf(backPath, back.rawQuery.orEmpty()).any { value ->
                value.split('/', '&', '=', ';').any { component ->
                    val decoded = try {
                        URLDecoder.decode(component.replace("+", "%2B"), "UTF-8")
                    } catch (_: IllegalArgumentException) {
                        return null
                    }
                    decoded.contains('\\') || decoded.startsWith('/') ||
                        decoded.equals("_magpie") || decoded.equals("_magpie/sign-in")
                }
            }
        ) {
            return null
        }
        return capture.key
    }

    private fun isSafeReturnPath(path: String): Boolean {
        var candidate = path
        repeat(4) {
            if (!candidate.startsWith('/') || candidate.startsWith("//") || candidate.startsWith("/\\")) {
                return false
            }
            if (candidate.getOrNull(1)?.isWhitespace() == true) return false
            if (candidate.trimEnd('/') == SIGN_IN_PATH) return false
            candidate = try {
                URLDecoder.decode(candidate.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                return false
            }
        }
        return true
    }

    private fun isSafeQuery(query: String): Boolean {
        var candidate = query
        repeat(4) {
            if (candidate.contains("back=/_magpie/sign-in", ignoreCase = true) ||
                candidate.contains("back=/%5Fmagpie/sign-in", ignoreCase = true) ||
                candidate.contains("back=/%255Fmagpie/sign-in", ignoreCase = true)
            ) {
                return false
            }
            candidate = try {
                URLDecoder.decode(candidate.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                return false
            }
        }
        return true
    }

    private fun isCanonicalRootPath(rawPath: String): Boolean =
        rawPath == "/" || rawPath.isEmpty()

    /** Returned to the login page to prevent a duplicate capture/reload loop. */
    fun acknowledgement(saved: Boolean): String =
        "document.documentElement.setAttribute('data-magpie-key-captured', '1');" +
            if (saved) "true;" else "false;"
}
