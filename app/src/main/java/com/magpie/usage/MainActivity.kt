package com.magpie.usage

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
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
    internal lateinit var preferences: android.content.SharedPreferences
        private set

    private lateinit var addressForm: ScrollView
    private lateinit var addressInput: EditText
    private lateinit var addressError: TextView
    private lateinit var openButton: Button
    private lateinit var webContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var loadingIndicator: LinearLayout
    private lateinit var errorPanel: LinearLayout
    private lateinit var panelBookStore: PanelBookStore
    private lateinit var defaultKeyStore: DefaultKeyStore
    private lateinit var navigationDrawer: NavigationDrawer

    private var savedOrigin: String? = null
    private var redirectAfterLoad = false
    private var firstLoadCleanUrl: String? = null
    private var clearingWebData = false
    private var navigationGeneration = 0L
    private var pendingLoginCapture: LoginKeyCapture.Capture? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (navigationDrawer.isOpen()) {
                navigationDrawer.close()
            } else if (webView.canGoBack()) {
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
        panelBookStore = PanelBookStore(this)
        defaultKeyStore = DefaultKeyStore(this)
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

        onBackPressedDispatcher.addCallback(this, backCallback)
        configureWebView()

        navigationDrawer = NavigationDrawer(
            activity = this,
            store = panelBookStore,
            root = findViewById(R.id.main_root),
            currentOrigin = { savedOrigin },
            openPanel = { selectPanel(it) },
            addPanel = { showAddressForm() },
            managePanels = { showAddressForm() },
            editPanel = { showPanelEditor(it) },
            deletePanel = { confirmDeletePanel(it) },
            editDefaultKey = { showDefaultKeyEditor() },
            clearDefaultKey = { confirmClearDefaultKey() },
            toggleReuse = { setReuseDefaultKey(it) },
            toggleCapture = { setCaptureLoginKey(it) },
            clearData = { confirmClearWebViewData() },
        )

        val book = panelBookStore.book
        if (book.entries.isNotEmpty()) panelBookStore.save(book)
        savedOrigin = book.mostRecent?.origin
        if (savedOrigin == null && preferences.contains(DefaultKeyPreferences.CAPTURE_ORIGIN)) {
            DefaultKeyPreferences.setCaptureOrigin(preferences, null)
        }

        val incoming = incomingAddress()
        if (incoming != null) {
            showAddressForm(incoming)
        } else {
            book.mostRecent?.let { selectPanel(it) } ?: showAddressForm()
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
            setSupportMultipleWindows(true)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        LoginKeyCapture.addJavascriptInterface(webView, ::captureLoginKey)
        webView.isVerticalScrollBarEnabled = true
        webView.webViewClient = PanelWebViewClient()
        webView.webChromeClient = PanelChromeClient()
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownloadOrRequestPermission(url, userAgent, contentDisposition, mimeType)
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (navigationDrawer.beginEdgeGesture(event)) return true
        val consumed = super.dispatchTouchEvent(event)
        return navigationDrawer.dispatchDrawerTouch(event) || consumed
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
        pendingLoginCapture = null
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
            PanelUrl.parse(rawInput)
        } catch (_: IllegalArgumentException) {
            showAddressError(getString(R.string.invalid_address))
            return
        }
        val currentBook = panelBookStore.book
        val existing = currentBook.find(target.origin)
        val entry = savePanel(
            currentBook,
            PanelBookState.upsert(
                currentBook,
                target.origin,
                label = existing?.label,
                lastUsedAt = System.currentTimeMillis(),
            ),
        ) ?: return
        if (!rawInput.trim().contains("://")) {
            Toast.makeText(this, R.string.default_scheme_added, Toast.LENGTH_SHORT).show()
        }
        addressInput.clearFocus()
        openSavedTarget(entry, target)
    }

    private fun savePanel(currentBook: PanelBook, book: PanelBook?): PanelEntry? {
        if (book == null) {
            Toast.makeText(this, R.string.address_book_full, Toast.LENGTH_LONG).show()
            return null
        }
        return try {
            panelBookStore.save(book)
            // Return the new/updated row, not whichever row happened to be
            // most recent before this save. Only an actually changed row is
            // eligible here, so the target cannot silently fall back.
            book.entries.firstOrNull { entry ->
                currentBook.find(entry.origin) != entry
            } ?: book.mostRecent
        } catch (_: IllegalStateException) {
            Toast.makeText(this, R.string.address_save_failed, Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun selectPanel(entry: PanelEntry) {
        if (clearingWebData) return
        val updated = panelBookStore.touch(entry.origin)
        val saved = updated.find(entry.origin) ?: return
        openSavedTarget(saved, PanelUrl.parse(PanelUrl.usageUrl(saved.origin)))
    }

    private fun openSavedTarget(entry: PanelEntry, target: PanelTarget) {
        if (!target.redirectAfterLoad) {
            DefaultKeyPreferences.setCaptureOrigin(
                preferences,
                entry.origin.takeIf { preferences.getBoolean(DefaultKeyPreferences.CAPTURE_ENABLED, false) },
            )
        }
        val reuse = preferences.getBoolean(DefaultKeyPreferences.REUSE_ENABLED, false)
        val reusableKey = DefaultKeyPolicy.reusableKey(
            defaultKeyStore.load(),
            reuseEnabled = reuse,
            captureEnabled = preferences.getBoolean(DefaultKeyPreferences.CAPTURE_ENABLED, false),
        )
        val withDefaultKey = DefaultKeyPolicy.appendForSavedPanel(
            panel = entry,
            cleanUrl = target.cleanUrl,
            key = reusableKey,
            reuseEnabled = reuse,
        )
        val initialUrl = withDefaultKey
            ?: if (target.redirectAfterLoad) target.initialUrl else target.cleanUrl
        savedOrigin = entry.origin
        navigationDrawer.refresh()
        openPanel(
            PanelTarget(
                origin = entry.origin,
                initialUrl = initialUrl,
                cleanUrl = target.cleanUrl,
                redirectAfterLoad = target.redirectAfterLoad || withDefaultKey != null,
            ),
        )
    }

    private fun openPanel(target: PanelTarget) {
        navigationGeneration++
        val generation = navigationGeneration
        pendingLoginCapture = null
        redirectAfterLoad = target.redirectAfterLoad
        firstLoadCleanUrl = if (target.redirectAfterLoad) target.cleanUrl else null
        clearingWebData = false
        hideError()
        showWebView()

        // This is the only app-initiated load that may contain a key. It is
        // neither logged nor persisted; completion clears it and the history.
        webView.stopLoading()
        webView.loadUrl(target.initialUrl)
        if (target.redirectAfterLoad) {
            webView.postDelayed({
                if (generation == navigationGeneration && redirectAfterLoad) {
                    finishFirstKeyLoad()
                }
            }, FIRST_KEY_HISTORY_GUARD_MS)
        }
    }

    private fun retryLoad() {
        // Never retain or replay a failed request. The clean saved-panel path
        // can use a deliberately reused default k; a pasted link's key is gone.
        val origin = savedOrigin ?: return
        val entry = panelBookStore.book.find(origin) ?: return
        openSavedTarget(entry, PanelUrl.parse(PanelUrl.usageUrl(origin)))
    }

    private fun finishFirstKeyLoad() {
        if (!redirectAfterLoad) return
        val origin = savedOrigin ?: return
        val cleanUrl = firstLoadCleanUrl ?: PanelUrl.usageUrl(origin)
        navigationGeneration++
        pendingLoginCapture = null
        redirectAfterLoad = false
        firstLoadCleanUrl = null

        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.loadUrl(cleanUrl)
    }

    private fun captureLoginKey(capture: LoginKeyCapture.Capture) {
        val origin = savedOrigin ?: return
        if (!preferences.getBoolean(DefaultKeyPreferences.CAPTURE_ENABLED, false)) return
        if (preferences.getString(DefaultKeyPreferences.CAPTURE_ORIGIN, null) != origin) return
        if (pendingLoginCapture != null) return
        val key = LoginKeyCapture.accept(capture, origin) ?: return
        val generation = navigationGeneration
        pendingLoginCapture = capture
        val saved = try {
            defaultKeyStore.save(key)
            true
        } catch (_: Exception) {
            false
        }
        webView.post {
            if (savedOrigin != origin || generation != navigationGeneration) {
                pendingLoginCapture = null
                return@post
            }
            webView.evaluateJavascript(LoginKeyCapture.acknowledgement(saved), null)
            pendingLoginCapture = null
            if (saved) {
                Toast.makeText(this, R.string.login_key_captured, Toast.LENGTH_LONG).show()
                navigationGeneration++
                redirectAfterLoad = true
                firstLoadCleanUrl = PanelUrl.usageUrl(origin)
                webView.postDelayed({
                    if (origin == savedOrigin && redirectAfterLoad) finishFirstKeyLoad()
                }, FIRST_KEY_HISTORY_GUARD_MS)
            } else {
                Toast.makeText(this, R.string.default_key_save_failed, Toast.LENGTH_LONG).show()
            }
        }
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
        pendingLoginCapture = null
        redirectAfterLoad = false
        firstLoadCleanUrl = null
        DefaultKeyPreferences.setCaptureOrigin(preferences, null)
        webView.stopLoading()
        closePopupWebViews()
        webView.loadUrl("about:blank")
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

    private fun showPanelEditor(entry: PanelEntry?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_panel_address, null)
        val labelInput = dialogView.findViewById<EditText>(R.id.panel_label_input)
        val originInput = dialogView.findViewById<EditText>(R.id.panel_origin_input)
        val editorError = dialogView.findViewById<TextView>(R.id.panel_editor_error)
        labelInput.setText(entry?.label.orEmpty())
        originInput.setText(entry?.origin.orEmpty())
        originInput.setSelection(originInput.text?.length ?: 0)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (entry == null) R.string.add_panel else R.string.edit_panel)
            .setView(dialogView)
            .setPositiveButton(R.string.save_panel, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                savePanelEditor(entry, labelInput, originInput, editorError, dialog)
            }
        }
        dialog.show()
    }

    private fun savePanelEditor(
        existing: PanelEntry?,
        labelInput: EditText,
        originInput: EditText,
        editorError: TextView,
        dialog: AlertDialog,
    ) {
        val target = try {
            PanelUrl.parse(originInput.text.toString())
        } catch (_: IllegalArgumentException) {
            showEditorError(editorError, R.string.invalid_address)
            return
        }
        if (existing != null && target.origin != existing.origin) {
            showEditorError(editorError, R.string.edit_origin_locked)
            return
        }
        val duplicate = panelBookStore.book.find(target.origin)
        if (existing == null && duplicate != null) {
            showEditorError(editorError, R.string.panel_already_exists)
            return
        }
        val updated = PanelBookState.upsert(
            panelBookStore.book,
            target.origin,
            label = labelInput.text.toString(),
            lastUsedAt = existing?.lastUsedAt ?: 0L,
        )
        val saved = try {
            if (updated == null) {
                showEditorError(editorError, R.string.address_book_full)
                return
            }
            panelBookStore.save(updated)
            updated.find(target.origin)
        } catch (_: IllegalStateException) {
            showEditorError(editorError, R.string.address_save_failed)
            return
        }
        if (saved == null) return
        if (existing != null && savedOrigin == existing.origin) {
            savedOrigin = saved.origin
        }
        navigationDrawer.refresh()
        dialog.dismiss()
    }

    private fun showEditorError(view: TextView, message: Int) {
        view.setText(message)
        view.visibility = View.VISIBLE
    }

    private fun confirmDeletePanel(entry: PanelEntry) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_panel_title, entry.label))
            .setMessage(R.string.delete_panel_message)
            .setPositiveButton(R.string.delete_panel_confirm) { _, _ ->
                if (panelBookStore.book.find(entry.origin) == null) return@setPositiveButton
                panelBookStore.remove(entry.origin)
                if (savedOrigin == entry.origin) {
                    savedOrigin = panelBookStore.book.mostRecent?.origin
                    DefaultKeyPreferences.setCaptureOrigin(preferences, null)
                    navigationDrawer.refresh()
                    showAddressForm()
                } else {
                    navigationDrawer.refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDefaultKeyEditor() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_default_key, null)
        val input = dialogView.findViewById<EditText>(R.id.default_key_input)
        val error = dialogView.findViewById<TextView>(R.id.default_key_error)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.save_default_key_title)
            .setView(dialogView)
            .setPositiveButton(R.string.save_default_key, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = input.text.toString()
                if (key.isBlank()) {
                    showEditorError(error, R.string.default_key_required)
                    return@setOnClickListener
                }
                val saved = try {
                    defaultKeyStore.save(key)
                    true
                } catch (_: Exception) {
                    false
                }
                if (!saved) {
                    showEditorError(error, R.string.default_key_save_failed)
                    return@setOnClickListener
                }
                input.text.clear()
                navigationDrawer.refresh()
                dialog.dismiss()
                Toast.makeText(this, R.string.default_key_saved, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun confirmClearDefaultKey() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_default_key_title)
            .setMessage(R.string.clear_default_key_message)
            .setPositiveButton(R.string.clear_default_key) { _, _ ->
                try {
                    defaultKeyStore.clear()
                } catch (_: IllegalStateException) {
                    Toast.makeText(this, R.string.default_key_save_failed, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                preferences.edit()
                    .putBoolean(DefaultKeyPreferences.REUSE_ENABLED, false)
                    .putBoolean(DefaultKeyPreferences.CAPTURE_ENABLED, false)
                    .apply()
                DefaultKeyPreferences.setCaptureOrigin(preferences, null)
                pendingLoginCapture = null
                navigationDrawer.refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setReuseDefaultKey(enabled: Boolean) {
        if (!enabled) {
            preferences.edit().putBoolean(DefaultKeyPreferences.REUSE_ENABLED, false).apply()
            navigationDrawer.refresh()
            return
        }
        if (!defaultKeyStore.isPresent() || defaultKeyStore.load() == null) {
            navigationDrawer.refresh()
            showDefaultKeyEditor()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reuse_enabled_title)
            .setMessage(R.string.reuse_enabled_message)
            .setPositiveButton(R.string.enable_reuse) { _, _ ->
                preferences.edit().putBoolean(DefaultKeyPreferences.REUSE_ENABLED, true).apply()
                navigationDrawer.refresh()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> navigationDrawer.refresh() }
            .show()
    }

    private fun setCaptureLoginKey(enabled: Boolean) {
        if (!enabled) {
            preferences.edit().putBoolean(DefaultKeyPreferences.CAPTURE_ENABLED, false).apply()
            DefaultKeyPreferences.setCaptureOrigin(preferences, null)
            pendingLoginCapture = null
            navigationDrawer.refresh()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_enabled_title)
            .setMessage(R.string.capture_enabled_message)
            .setPositiveButton(R.string.enable_capture) { _, _ ->
                preferences.edit().putBoolean(DefaultKeyPreferences.CAPTURE_ENABLED, true).apply()
                savedOrigin?.let { DefaultKeyPreferences.setCaptureOrigin(preferences, it) }
                navigationDrawer.refresh()
                if (webContainer.visibility == View.VISIBLE) {
                    val url = webView.url
                    if (url != null) {
                        LoginKeyCapture.injectIfEligible(
                            view = webView,
                            url = url,
                            panelOrigin = savedOrigin,
                            captureEnabled = true,
                        )
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> navigationDrawer.refresh() }
            .show()
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
        pendingLoginCapture = null
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
            if (completedHttpPanel &&
                preferences.getBoolean(DefaultKeyPreferences.CAPTURE_ENABLED, false) &&
                preferences.getString(DefaultKeyPreferences.CAPTURE_ORIGIN, null) == targetOrigin
            ) {
                LoginKeyCapture.injectIfEligible(
                    view = view,
                    url = url,
                    panelOrigin = targetOrigin,
                    captureEnabled = true,
                )
            }
            if (redirectAfterLoad && completedHttpPanel) {
                finishFirstKeyLoad()
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) {
                showError()
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            // HTTP 401 is the panel's native key-login page, not a network error.
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
                                PanelUrl.parse(destination)
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
        pendingLoginCapture = null
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
        internal const val PREFERENCES_NAME = "magpie_usage"
        private const val ACTION_VIEW = "android.intent.action.VIEW"
        private const val FIRST_KEY_HISTORY_GUARD_MS = 1_500L
    }
}
