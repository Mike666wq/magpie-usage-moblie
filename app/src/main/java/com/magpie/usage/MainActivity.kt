package com.magpie.usage

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private lateinit var preferences: android.content.SharedPreferences

    private lateinit var addressForm: ScrollView
    private lateinit var addressInput: EditText
    private lateinit var addressError: TextView
    private lateinit var openButton: Button
    private lateinit var webContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var loadingIndicator: LinearLayout
    private lateinit var errorPanel: LinearLayout

    private var savedOrigin: String? = null
    private var redirectAfterLoad = false
    private var firstLoadCleanUrl: String? = null
    private var clearingWebData = false
    private var navigationGeneration = 0L

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        addressForm = findViewById(R.id.address_form)
        addressInput = findViewById(R.id.address_input)
        addressError = findViewById(R.id.address_error)
        openButton = findViewById(R.id.open_button)
        webContainer = findViewById(R.id.web_container)
        webView = findViewById(R.id.web_view)
        loadingIndicator = findViewById(R.id.loading_indicator)
        errorPanel = findViewById(R.id.error_panel)

        ViewCompat.setOnApplyWindowInsetsListener(webContainer) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        openButton.setOnClickListener { openTypedAddress() }
        addressInput.setOnEditorActionListener { _, actionId, event ->
            val isGoAction = actionId == EditorInfo.IME_ACTION_GO
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_UP
            if (isGoAction || isEnter) {
                openTypedAddress()
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.change_from_error_button).setOnClickListener {
            showAddressForm()
        }
        findViewById<Button>(R.id.retry_button).setOnClickListener { retryLoad() }
        findViewById<Button>(R.id.change_address_button).setOnClickListener {
            if (!clearingWebData) showAddressForm()
        }
        findViewById<Button>(R.id.clear_data_button).setOnClickListener {
            if (!clearingWebData) confirmClearWebViewData()
        }

        onBackPressedDispatcher.addCallback(this, backCallback)
        configureWebView()

        savedOrigin = PanelAddress.storedOrigin(preferences)
        val incoming = incomingAddress()
        if (incoming != null) {
            showAddressForm(incoming)
        } else {
            savedOrigin?.let {
                val cleanUrl = PanelAddress.usageUrl(it)
                openPanel(PanelTarget(it, cleanUrl, cleanUrl, false))
            } ?: showAddressForm()
        }
    }

    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowContentAccess = false
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.isVerticalScrollBarEnabled = true
        webView.webViewClient = PanelWebViewClient()
        webView.webChromeClient = PanelChromeClient()
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownloadOrRequestPermission(url, userAgent, contentDisposition, mimeType)
        })
        webView.settings.setSupportMultipleWindows(true)
    }

    private data class PendingDownload(
        val url: String,
        val userAgent: String,
        val contentDisposition: String?,
        val mimeType: String?,
    )

    private var pendingDownload: PendingDownload? = null

    private fun enqueueDownloadOrRequestPermission(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        val download = PendingDownload(url, userAgent, contentDisposition, mimeType)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Only API 24-28 may need this legacy public-directory write.
            pendingDownload = download
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        enqueueDownload(download)
    }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val download = pendingDownload
            pendingDownload = null
            if (granted && download != null) {
                enqueueDownload(download)
            } else {
                Toast.makeText(this, R.string.download_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    private fun enqueueDownload(download: PendingDownload) {
        DownloadHandler.enqueue(
            this,
            download.url,
            download.userAgent,
            download.contentDisposition,
            download.mimeType,
            savedOrigin,
        )
    }

    private fun clearWebViewDataAndReturnToPanel() {
        val origin = savedOrigin
        if (clearingWebData) return
        clearingWebData = true
        navigationGeneration++
        val generation = navigationGeneration
        redirectAfterLoad = false
        firstLoadCleanUrl = null
        hideError()
        webView.stopLoading()
        WebViewDataManager.clear(
            webView,
            CookieManager.getInstance(),
        ) {
            // removeAllCookies is asynchronous. Ignore a stale callback after
            // another navigation or Activity teardown.
            if (generation == navigationGeneration && !isFinishing && !isDestroyed) {
                // Cookie deletion alone does not unload the authenticated
                // document. Replace it and close popups before showing the
                // address form so old page memory cannot remain active.
                clearingWebData = false
                closePopupWebViews()
                webView.loadUrl("about:blank")
                webView.clearHistory()
                showAddressForm(origin)
            }
        }
    }

    private fun confirmClearWebViewData() {
        val origin = savedOrigin
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_data_title)
            .setMessage(R.string.clear_data_message)
            .setPositiveButton(R.string.clear_data_confirm) { _, _ ->
                if (origin != null) {
                    // Keep the same-origin trust boundary valid for the blank
                    // page callback; the blank page itself is not persisted.
                    savedOrigin = origin
                    clearWebViewDataAndReturnToPanel()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openTypedAddress() {
        if (clearingWebData) return
        val rawInput = addressInput.text?.toString().orEmpty()
        addressError.visibility = View.GONE
        if (rawInput.isBlank()) {
            showAddressError(getString(R.string.enter_address))
            return
        }

        val target = try {
            PanelAddress.parse(rawInput)
        } catch (_: IllegalArgumentException) {
            showAddressError(getString(R.string.invalid_address))
            return
        }

        if (!rawInput.trim().contains("://")) {
            Toast.makeText(this, R.string.default_scheme_added, Toast.LENGTH_SHORT).show()
        }

        // Only the canonical origin crosses the persistence boundary.
        PanelAddress.saveOrigin(this, target.origin)
        savedOrigin = target.origin
        addressInput.clearFocus()
        openPanel(target)
    }

    private fun openPanel(target: PanelTarget) {
        navigationGeneration++
        val generation = navigationGeneration
        redirectAfterLoad = target.redirectAfterLoad
        firstLoadCleanUrl = if (target.redirectAfterLoad) target.cleanUrl else null
        clearingWebData = false
        hideError()
        showWebView()

        // This is the only load that may contain a key. It is neither logged
        // nor persisted; completion clears the app reference and history.
        webView.stopLoading()
        webView.loadUrl(target.initialUrl)
        if (target.redirectAfterLoad) {
            // Do not rely on a delayed about:blank callback to erase history.
            webView.postDelayed({
                if (generation == navigationGeneration && redirectAfterLoad) {
                    finishFirstKeyLoad()
                }
            }, FIRST_KEY_HISTORY_GUARD_MS)
        }
    }

    private fun retryLoad() {
        // Never retain or replay a failed request: its URL can contain k.
        // Retry from the persisted origin, letting the native login page run
        // again if the automatic cookie exchange did not complete.
        val origin = savedOrigin ?: return
        val cleanUrl = PanelAddress.usageUrl(origin)
        openPanel(PanelTarget(origin, cleanUrl, cleanUrl, false))
    }

    private fun finishFirstKeyLoad() {
        if (!redirectAfterLoad) return
        val origin = savedOrigin ?: return
        val cleanUrl = firstLoadCleanUrl ?: PanelAddress.usageUrl(origin)
        navigationGeneration++
        redirectAfterLoad = false
        firstLoadCleanUrl = null

        webView.stopLoading()
        // Replacing the key-bearing entry is the only navigation that can
        // change its URL. clearHistory then removes any prior entries before
        // the clean page becomes the first item in the back stack.
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.loadUrl(cleanUrl)
    }

    private fun closePopupWebViews() {
        for (index in webContainer.childCount - 1 downTo 0) {
            val child = webContainer.getChildAt(index)
            if (child !== webView && child is WebView) {
                webContainer.removeViewAt(index)
                child.destroy()
            }
        }
    }

    private fun showAddressForm(prefill: String? = null) {
        navigationGeneration++
        redirectAfterLoad = false
        firstLoadCleanUrl = null
        webView.stopLoading()
        closePopupWebViews()
        webView.loadUrl("about:blank")
        // Replace any visible document and clear immediately; do not depend on
        // a callback from a WebView that is about to be hidden.
        webView.clearHistory()

        addressInput.setText("")
        addressError.text = ""
        addressError.visibility = View.GONE
        addressForm.visibility = View.VISIBLE
        webContainer.visibility = View.GONE
        loadingIndicator.visibility = View.GONE
        errorPanel.visibility = View.GONE
        backCallback.isEnabled = false

        val value = prefill ?: savedOrigin
        addressInput.setText(value.orEmpty())
        addressInput.setSelection(addressInput.text?.length ?: 0)
        addressInput.requestFocus()
    }

    private fun showWebView() {
        addressForm.visibility = View.GONE
        webContainer.visibility = View.VISIBLE
        backCallback.isEnabled = true
    }

    private fun showLoading(isLoading: Boolean) {
        loadingIndicator.visibility = if (isLoading && errorPanel.visibility != View.VISIBLE) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun showError() {
        navigationGeneration++
        redirectAfterLoad = false
        firstLoadCleanUrl = null
        loadingIndicator.visibility = View.GONE
        errorPanel.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorPanel.visibility = View.GONE
    }

    private fun showAddressError(message: String) {
        addressError.text = message
        addressError.visibility = View.VISIBLE
        addressInput.requestFocus()
    }

    private fun incomingAddress(): String? {
        if (intent?.action != ACTION_VIEW) return null
        return intent.data?.toString()
    }

    private inner class PanelWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url.toString()
            val origin = savedOrigin

            if (NavigationPolicy.forUrl(url) == NavigationAction.OPEN_EXTERNALLY) {
                ExternalNavigator.open(this@MainActivity, url)
                return true
            }

            // Keep the configured panel's top-level trust boundary. A redirect
            // to another HTTP(S) origin is not the same as a user-opened link.
            val internalBlank = NavigationPolicy.isInternalBlank(url)
            if (request.isForMainFrame &&
                !internalBlank &&
                origin != null &&
                !NavigationPolicy.isPanelTopLevelUrl(url, origin)
            ) {
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (!url.isNullOrBlank() && !isInternalBlank(url)) {
                showLoading(true)
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            showLoading(false)
            val targetOrigin = savedOrigin
            val completedHttpPanel = url != null && targetOrigin != null &&
                NavigationPolicy.isPanelTopLevelUrl(url, targetOrigin)
            if (redirectAfterLoad && completedHttpPanel) {
                // Only the panel's HTTP(S) document triggers replacement.
                // Internal about: pages and network failures must not cut off
                // the key exchange before it finishes.
                finishFirstKeyLoad()
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) {
                // Error text/codes and the failing URL are deliberately not
                // logged or rendered.
                showError()
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            // HTTP 401 is the panel's native key-login page, not a network
            // error. Other HTTP statuses remain visible in the web page.
        }
    }

    private inner class PanelChromeClient : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message,
        ): Boolean {
            if (!isUserGesture) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val popup = WebView(this@MainActivity).apply {
                configurePopupWebView(this)
            }
            webContainer.addView(
                popup,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            webContainer.removeView(window)
            window.destroy()
        }

        private fun configurePopupWebView(popup: WebView) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
            popup.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowContentAccess = false
                allowFileAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val destination = request.url.toString()
                    if (NavigationPolicy.isInternalBlank(destination)) {
                        return false
                    }
                    return when (NavigationPolicy.forUrl(destination)) {
                        NavigationAction.OPEN_EXTERNALLY -> {
                            ExternalNavigator.open(this@MainActivity, destination)
                            true
                        }
                        NavigationAction.KEEP_IN_WEB_VIEW -> {
                            val target = try {
                                PanelAddress.parse(destination)
                            } catch (_: IllegalArgumentException) {
                                return true
                            }
                            if (!sameOrigin(target.origin, savedOrigin)) {
                                ExternalNavigator.open(this@MainActivity, destination)
                                true
                            } else {
                                false
                            }
                        }
                    }
                }
            }
            popup.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) {
                    webContainer.removeView(window)
                    window.destroy()
                }
            }
            popup.setDownloadListener(DownloadListener { url, userAgent, disposition, mimeType, _ ->
                enqueueDownloadOrRequestPermission(url, userAgent, disposition, mimeType)
            })
            popup.settings.setSupportMultipleWindows(true)
        }
    }

    private fun sameOrigin(first: String, second: String?): Boolean = first == second

    private fun isInternalBlank(url: String?): Boolean =
        url != null && NavigationPolicy.isInternalBlank(url)

    override fun onDestroy() {
        navigationGeneration++
        firstLoadCleanUrl = null
        webView.stopLoading()
        val childCount = webContainer.childCount
        for (index in childCount - 1 downTo 0) {
            val child = webContainer.getChildAt(index)
            webContainer.removeViewAt(index)
            if (child is WebView) child.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val PREFERENCES_NAME = "magpie_usage"
        private const val ACTION_VIEW = "android.intent.action.VIEW"
        private const val FIRST_KEY_HISTORY_GUARD_MS = 1_500L
    }
}
