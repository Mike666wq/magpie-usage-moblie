package com.magpie.usage

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import kotlin.math.abs

/** A small native drawer driven by a persistent edge handle and edge swipes. */
internal class NavigationDrawer(
    private val activity: MainActivity,
    private val store: PanelBookStore,
    root: View,
    private val currentOrigin: () -> String?,
    private val openPanel: (PanelEntry) -> Unit,
    private val addPanel: () -> Unit,
    private val managePanels: () -> Unit,
    private val editPanel: (PanelEntry) -> Unit,
    private val deletePanel: (PanelEntry) -> Unit,
    private val editDefaultKey: () -> Unit,
    private val clearDefaultKey: () -> Unit,
    private val toggleReuse: (Boolean) -> Unit,
    private val toggleCapture: (Boolean) -> Unit,
    private val clearData: () -> Unit,
) {
    private val drawer: LinearLayout = root.findViewById(R.id.navigation_drawer)
    private val scrim: View = root.findViewById(R.id.drawer_scrim)
    private val handle: View = root.findViewById(R.id.drawer_handle)
    private val panelList: LinearLayout = root.findViewById(R.id.panel_list_container)
    private val savedPanelsLabel: TextView = root.findViewById(R.id.saved_panels_label)
    private val defaultKeyStatus: TextView = root.findViewById(R.id.default_key_status)
    private val reuseSwitch: SwitchCompat = root.findViewById(R.id.reuse_default_key_switch)
    private val captureSwitch: SwitchCompat = root.findViewById(R.id.capture_login_key_switch)
    private val clearKeyButton: Button = root.findViewById(R.id.clear_default_key_button)

    private var drawerWidth = 320f
    private var visible = false
    private var trackingGesture = false
    private var startX = 0f
    private var startY = 0f
    private var dragStarted = false

    init {
        drawer.post { drawerWidth = drawer.width.toFloat().takeIf { it > 0 } ?: 320f }
        handle.setOnClickListener { if (!trackingGesture) open() }
        root.findViewById<ImageButton>(R.id.close_drawer_button).setOnClickListener { close() }
        scrim.setOnClickListener { close() }
        root.findViewById<Button>(R.id.add_panel_button).setOnClickListener {
            close()
            addPanel()
        }
        root.findViewById<Button>(R.id.manage_panels_button).setOnClickListener {
            close()
            managePanels()
        }
        root.findViewById<Button>(R.id.edit_default_key_button).setOnClickListener {
            close()
            editDefaultKey()
        }
        clearKeyButton.setOnClickListener {
            close()
            clearDefaultKey()
        }
        root.findViewById<Button>(R.id.clear_data_button).setOnClickListener {
            close()
            clearData()
        }
        reuseSwitch.setOnClickListener { toggleReuse(reuseSwitch.isChecked) }
        captureSwitch.setOnClickListener { toggleCapture(captureSwitch.isChecked) }
        refresh()
    }

    fun refresh() {
        panelList.removeAllViews()
        val book = store.book
        savedPanelsLabel.text = if (book.entries.isEmpty()) {
            activity.getString(R.string.address_book_empty)
        } else {
            activity.getString(R.string.saved_panels)
        }
        val current = currentOrigin()
        book.sortedForDisplay().forEach { entry ->
            panelList.addView(createRow(entry, entry.origin == current))
        }

        val keyStore = DefaultKeyStore(activity)
        val hasKey = keyStore.isPresent()
        val readable = if (hasKey) keyStore.load() != null else true
        defaultKeyStatus.text = activity.getString(
            when {
                !hasKey -> R.string.default_key_status_none
                !readable -> R.string.default_key_status_unavailable
                else -> R.string.default_key_status_saved
            },
        )
        clearKeyButton.isEnabled = hasKey
        reuseSwitch.isChecked = activity.preferences.getBoolean(DefaultKeyPreferences.REUSE_ENABLED, false)
        captureSwitch.isChecked = activity.preferences.getBoolean(DefaultKeyPreferences.CAPTURE_ENABLED, false)
    }

    fun isOpen(): Boolean = visible

    fun open() {
        if (visible) return
        visible = true
        refresh()
        drawer.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        drawer.translationX = 0f
        scrim.alpha = 1f
        drawer.requestFocus()
    }

    fun close() {
        if (!visible) return
        visible = false
        scrim.visibility = View.GONE
        drawer.visibility = View.GONE
        drawer.translationX = -drawerWidth
    }

    private fun createRow(entry: PanelEntry, selected: Boolean): View {
        val row = LayoutInflater.from(activity).inflate(R.layout.item_panel_address, panelList, false)
        row.setBackgroundColor(if (selected) 0xFF337A5C.toInt() else 0x00000000)
        row.contentDescription = if (selected) {
            "${entry.label}，${activity.getString(R.string.current_panel)}"
        } else {
            entry.label
        }
        row.findViewById<TextView>(R.id.panel_label).text = entry.label
        row.findViewById<TextView>(R.id.panel_origin).text = entry.origin
        row.findViewById<LinearLayout>(R.id.open_panel_row).setOnClickListener {
            close()
            openPanel(entry)
        }
        row.findViewById<ImageButton>(R.id.edit_panel_button).setOnClickListener {
            close()
            editPanel(entry)
        }
        row.findViewById<ImageButton>(R.id.delete_panel_button).setOnClickListener {
            close()
            deletePanel(entry)
        }
        return row
    }

    /**
     * Remembers a possible edge gesture without consuming it. In particular,
     * ACTION_DOWN must still reach the WebView so taps, vertical scrolling and
     * horizontal page gestures on the left keep their normal behaviour.
     */
    fun beginEdgeGesture(event: MotionEvent): Boolean {
        if (visible || event.actionMasked != MotionEvent.ACTION_DOWN) return false
        if (event.rawX > EDGE_ZONE_DP * activity.resources.displayMetrics.density) return false
        trackingGesture = true
        startX = event.rawX
        startY = event.rawY
        dragStarted = false
        return false
    }

    /** Called only after normal dispatch has declined the gesture. */
    fun dispatchDrawerTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!trackingGesture) return false
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                if (!dragStarted) {
                    val slop = ViewConfiguration.get(activity).scaledTouchSlop
                    val edgeDirectionLocked = abs(dy) > slop || dx < -slop ||
                        (abs(dx) > slop && dx <= abs(dy))
                    if (edgeDirectionLocked) trackingGesture = false
                    val startedEdgeDrag = trackingGesture && dx > slop && dx > abs(dy)
                    if (!startedEdgeDrag) return false
                    dragStarted = true
                    drawer.visibility = View.VISIBLE
                }
                val progress = (dx / drawerWidth).coerceIn(0f, 1f)
                drawer.translationX = -drawerWidth + progress * drawerWidth
                scrim.visibility = View.VISIBLE
                scrim.alpha = progress
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!trackingGesture) return false
                val shouldOpen = event.actionMasked == MotionEvent.ACTION_UP && dragStarted &&
                    event.rawX - startX > drawerWidth * OPEN_THRESHOLD
                trackingGesture = false
                if (dragStarted) {
                    if (shouldOpen) open() else close()
                    dragStarted = false
                    return true
                }
            }
        }
        return false
    }

    private companion object {
        const val EDGE_ZONE_DP = 28f
        const val OPEN_THRESHOLD = 0.35f
    }
}
