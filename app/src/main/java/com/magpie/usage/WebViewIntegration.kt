package com.magpie.usage

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

internal object WebViewDataManager {
    fun clear(webView: WebView, cookieManager: CookieManager, onFinished: () -> Unit) {
        // Cookie deletion is asynchronous. Keep the current document visible
        // until removal completes so an immediate reload cannot reuse the old
        // login cookie.
        cookieManager.removeAllCookies {
            webView.clearCache(true)
            webView.clearFormData()
            webView.clearHistory()
            webView.clearSslPreferences()
            WebStorageManager.clear()
            cookieManager.flush()
            onFinished()
        }
    }
}

internal object WebStorageManager {
    fun clear() {
        android.webkit.WebStorage.getInstance().deleteAllData()
    }
}

internal object ExternalNavigator {
    fun open(activity: AppCompatActivity, rawUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.no_external_app, Toast.LENGTH_SHORT).show()
        }
    }
}

internal object DownloadHandler {
    fun enqueue(
        activity: AppCompatActivity,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        panelOrigin: String?,
    ) {
        val requestUri = Uri.parse(url)
        val extension = MimeTypeMap.getFileExtensionFromUrl(url)
        val inferredMimeType = if (extension.isNullOrEmpty()) {
            null
        } else {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        }
        val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() } ?: inferredMimeType
        val fileName = URLUtil.guessFileName(url, contentDisposition, resolvedMimeType)
        val request = DownloadManager.Request(requestUri).apply {
            addRequestHeader("User-Agent", userAgent)
            // DownloadManager uses its own network stack. magpie's page key
            // is an HttpOnly WebView cookie, so forward it explicitly for an
            // authenticated CSV/attachment download.
            val downloadOrigin = try {
                PanelAddress.parse(url).origin
            } catch (_: IllegalArgumentException) {
                null
            }
            if (downloadOrigin != null && downloadOrigin == panelOrigin) {
                val requestPort = requestUri.port.takeIf { it >= 0 }
                    ?: if (requestUri.scheme.equals("https", ignoreCase = true)) 443 else 80
                val cookieName = "magpie_web_$requestPort"
                CookieManager.getInstance().getCookie(requestUri.toString())?.let { cookies ->
                    cookies.split(';').forEach { pair ->
                        val name = pair.substringBefore('=', missingDelimiterValue = "").trim()
                        if (name == cookieName) {
                            addRequestHeader("Cookie", pair.trim())
                        }
                    }
                }
            }
            resolvedMimeType?.let { setMimeType(it) }
            setTitle(fileName)
            setDescription(activity.getString(R.string.download_started))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(activity, R.string.download_started, Toast.LENGTH_SHORT).show()
    }
}
