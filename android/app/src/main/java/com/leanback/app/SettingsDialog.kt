package com.leanback.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsDialog(
    context: Context,
    private val prefs: Prefs,
    private val lanClient: LanClient,
    private val onTouchpadConfigChanged: () -> Unit,
    private val onClosed: () -> Unit,
) : Dialog(context) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var scanJob: Job? = null

    private lateinit var statusView: TextView
    private lateinit var actionBtn: Button
    private lateinit var scanBtn: TextView
    private lateinit var discoveredContainer: LinearLayout
    private lateinit var manualInput: EditText
    private lateinit var speedLabel: TextView
    private lateinit var forgetBtn: TextView

    private val dp = context.resources.displayMetrics.density

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildContent())
        window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#111114"))
                cornerRadius = 18f * dp
                setStroke((1 * dp).toInt(), Color.parseColor("#27272A"))
            })
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }

        setOnDismissListener {
            scanJob?.cancel()
            onClosed()
        }

        updateState(lanClient.getState())
        startScan()
    }

    fun updateState(state: ConnectionState) {
        if (!::statusView.isInitialized) return
        when (state) {
            is ConnectionState.Connected -> {
                statusView.text = "Connected to ${state.serverName} (${state.host}:${state.port})"
                statusView.setTextColor(Color.parseColor("#34D399"))
                actionBtn.visibility = View.VISIBLE
                actionBtn.text = "Disconnect"
                actionBtn.setOnClickListener { lanClient.disconnect(manual = true) }
            }

            is ConnectionState.PendingApproval -> {
                statusView.text = "Accept on PC (${state.serverName}) — Code: ${state.code}"
                statusView.setTextColor(Color.parseColor("#FBBF24"))
                actionBtn.visibility = View.VISIBLE
                actionBtn.text = "Cancel"
                actionBtn.setOnClickListener { lanClient.disconnect(manual = true) }
            }

            is ConnectionState.Connecting -> {
                statusView.text = "Connecting to ${state.host}:${state.port}..."
                statusView.setTextColor(Color.parseColor("#FBBF24"))
                actionBtn.visibility = View.VISIBLE
                actionBtn.text = "Cancel"
                actionBtn.setOnClickListener { lanClient.disconnect(manual = true) }
            }

            is ConnectionState.Disconnected -> {
                val msg = when {
                    state.rejected -> state.reason ?: "Denied on PC"
                    !state.reason.isNullOrBlank() -> "Disconnected (${state.reason})"
                    else -> "Not connected"
                }
                statusView.text = msg
                statusView.setTextColor(
                    if (state.rejected) Color.parseColor("#F87171") else Color.parseColor("#A1A1AA")
                )
                val savedHost = prefs.lastHost
                if (!savedHost.isNullOrBlank()) {
                    actionBtn.visibility = View.VISIBLE
                    actionBtn.text = "Reconnect (${savedHost})"
                    actionBtn.setOnClickListener {
                        lanClient.connect(savedHost, prefs.lastPort, prefs.lastServerName, userInitiated = true)
                    }
                } else {
                    actionBtn.visibility = View.GONE
                }
            }
        }
        forgetBtn.visibility = if (prefs.lastHost.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun startScan() {
        scanJob?.cancel()
        scanBtn.text = "Scanning..."
        scanBtn.isEnabled = false
        discoveredContainer.removeAllViews()
        discoveredContainer.addView(makeHintText("Searching local Wi-Fi for LeanBack PCs..."))

        scanJob = scope.launch {
            val found = LanDiscovery.scan { liveList ->
                renderDiscovered(liveList)
            }
            renderDiscovered(found)
            scanBtn.text = "Scan"
            scanBtn.isEnabled = true
        }
    }

    private fun renderDiscovered(pcs: List<DiscoveredPc>) {
        discoveredContainer.removeAllViews()
        if (pcs.isEmpty()) {
            discoveredContainer.addView(
                makeHintText("No PCs found yet. Run \"npx leanback\" on your PC on the same Wi-Fi.")
            )
            return
        }
        for (pc in pcs) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#18181B"))
                    cornerRadius = 10f * dp
                    setStroke(1, Color.parseColor("#27272A"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
            }

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(context).apply {
                text = pc.name
                setTextColor(Color.parseColor("#F4F4F5"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            textCol.addView(TextView(context).apply {
                text = "${pc.host}:${pc.port}"
                setTextColor(Color.parseColor("#71717A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
            row.addView(textCol)

            row.addView(TextView(context).apply {
                text = "Connect"
                setTextColor(Color.parseColor("#22D3EE"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })

            row.setOnClickListener {
                manualInput.setText("${pc.host}:${pc.port}")
                lanClient.connect(pc.host, pc.port, pc.name, userInitiated = true)
            }
            discoveredContainer.addView(row)
        }
    }

    private fun buildContent(): View {
        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        scroll.addView(root)

        // Header row
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        header.addView(TextView(context).apply {
            text = "LeanBack Settings"
            setTextColor(Color.parseColor("#F4F4F5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(context).apply {
            text = "Done"
            setTextColor(Color.parseColor("#22D3EE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), dp(6), dp(4), dp(6))
            setOnClickListener { dismiss() }
        })
        root.addView(header)

        // Status
        statusView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(statusView)

        actionBtn = makeDarkButton("").apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }
        }
        root.addView(actionBtn)

        // Nearby PCs
        val nearbyHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        nearbyHeader.addView(TextView(context).apply {
            text = "Nearby PCs"
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        scanBtn = TextView(context).apply {
            text = "Scan"
            setTextColor(Color.parseColor("#22D3EE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { startScan() }
        }
        nearbyHeader.addView(scanBtn)
        root.addView(nearbyHeader)

        discoveredContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        root.addView(discoveredContainer)

        // Manual IP:Port
        root.addView(TextView(context).apply {
            text = "Manual IP Address"
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        })

        val manualRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(16) }
        }
        manualInput = EditText(context).apply {
            hint = "192.168.1.42:7890"
            setHintTextColor(Color.parseColor("#52525B"))
            setTextColor(Color.parseColor("#F4F4F5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            val savedHost = prefs.lastHost
            if (!savedHost.isNullOrBlank()) {
                setText("$savedHost:${prefs.lastPort}")
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18181B"))
                cornerRadius = 10f * dp
                setStroke(1, Color.parseColor("#27272A"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            }
        }
        val connectBtn = makeDarkButton("Connect").apply {
            setOnClickListener {
                val (host, port) = parseHostPort(manualInput.text.toString())
                if (host.isNotEmpty()) {
                    lanClient.connect(host, port, userInitiated = true)
                }
            }
        }
        manualRow.addView(manualInput)
        manualRow.addView(connectBtn)
        root.addView(manualRow)

        // Sensitivity slider
        speedLabel = TextView(context).apply {
            text = formatSpeed(prefs.sensitivity)
            setTextColor(Color.parseColor("#D4D4D8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        root.addView(speedLabel)

        val seekBar = SeekBar(context).apply {
            max = 31 // 0.4 to 3.5 in steps of 0.1
            progress = ((prefs.sensitivity - 0.4f) * 10f).toInt().coerceIn(0, 31)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val speed = 0.4f + value * 0.1f
                    prefs.sensitivity = speed
                    speedLabel.text = formatSpeed(speed)
                    onTouchpadConfigChanged()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(10)
            }
        }
        root.addView(seekBar)

        // Click mode selector (2 options)
        root.addView(TextView(context).apply {
            text = "Click Mode"
            setTextColor(Color.parseColor("#D4D4D8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        })

        val modeHintView = TextView(context).apply {
            setTextColor(Color.parseColor("#71717A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(10)
            }
        }

        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val tapOptionBtn = TextView(context).apply {
            text = "Tap touchpad"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            }
        }
        val buttonsOptionBtn = TextView(context).apply {
            text = "Click buttons"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val layoutSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(2)
            }
        }
        layoutSection.addView(TextView(context).apply {
            text = "Button Layout"
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        })
        val layoutRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val sideBySideBtn = TextView(context).apply {
            text = "Side-by-side"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            }
        }
        val stackedBtn = TextView(context).apply {
            text = "Left side (R/L)"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        layoutRow.addView(sideBySideBtn)
        layoutRow.addView(stackedBtn)
        layoutSection.addView(layoutRow)

        fun refreshClickModeUi() {
            val useButtons = prefs.buttonClickMode
            val stacked = prefs.buttonLayoutStacked
            fun styleOption(view: TextView, selected: Boolean) {
                view.setTextColor(
                    if (selected) Color.parseColor("#22D3EE") else Color.parseColor("#A1A1AA")
                )
                view.background = GradientDrawable().apply {
                    cornerRadius = 10f * dp
                    setColor(if (selected) Color.parseColor("#11282F") else Color.parseColor("#18181B"))
                    setStroke(1, if (selected) Color.parseColor("#22D3EE") else Color.parseColor("#27272A"))
                }
            }
            styleOption(tapOptionBtn, !useButtons)
            styleOption(buttonsOptionBtn, useButtons)
            styleOption(sideBySideBtn, !stacked)
            styleOption(stackedBtn, stacked)
            layoutSection.visibility = if (useButtons) View.VISIBLE else View.GONE
            modeHintView.text = when {
                !useButtons -> "Default: tap touchpad to left-click, 2-finger tap to right-click."
                stacked -> "Square 1:1 Right-over-Left buttons on the bottom-left. Right side stays touchpad."
                else -> "Left & Right buttons side-by-side below the touchpad (no accidental taps)."
            }
        }

        tapOptionBtn.setOnClickListener {
            prefs.buttonClickMode = false
            refreshClickModeUi()
            onTouchpadConfigChanged()
        }
        buttonsOptionBtn.setOnClickListener {
            prefs.buttonClickMode = true
            refreshClickModeUi()
            onTouchpadConfigChanged()
        }
        sideBySideBtn.setOnClickListener {
            prefs.buttonLayoutStacked = false
            refreshClickModeUi()
            onTouchpadConfigChanged()
        }
        stackedBtn.setOnClickListener {
            prefs.buttonLayoutStacked = true
            refreshClickModeUi()
            onTouchpadConfigChanged()
        }

        modeRow.addView(tapOptionBtn)
        modeRow.addView(buttonsOptionBtn)
        root.addView(modeRow)
        root.addView(layoutSection)
        refreshClickModeUi()
        root.addView(modeHintView)

        root.addView(makeSwitchRow("TikTok / Shorts mode (scroll + double-tap like)", prefs.tiktokMode) { checked ->
            prefs.tiktokMode = checked
            onTouchpadConfigChanged()
        })

        root.addView(makeSwitchRow("Natural scroll (phone-style)", prefs.naturalScroll) { checked ->
            prefs.naturalScroll = checked
            onTouchpadConfigChanged()
        })

        root.addView(makeSwitchRow("Keep screen on while connected", prefs.keepScreenOn) { checked ->
            prefs.keepScreenOn = checked
            onTouchpadConfigChanged()
        })

        forgetBtn = TextView(context).apply {
            text = "Forget saved PC & pairing token"
            setTextColor(Color.parseColor("#F87171"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener {
                lanClient.disconnect(manual = true)
                prefs.clearSavedServer()
                manualInput.setText("")
                updateState(lanClient.getState())
            }
        }
        root.addView(forgetBtn)

        return scroll
    }

    private fun makeSwitchRow(label: String, initial: Boolean, onChanged: (Boolean) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#D4D4D8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(SwitchCompat(context).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        })
        return row
    }

    private fun makeDarkButton(label: String): Button = Button(context).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.parseColor("#F4F4F5"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#27272A"))
            cornerRadius = 10f * dp
        }
        setPadding(dp(14), dp(8), dp(14), dp(8))
    }

    private fun makeHintText(msg: String): TextView = TextView(context).apply {
        text = msg
        setTextColor(Color.parseColor("#71717A"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun formatSpeed(speed: Float): String =
        String.format(Locale.US, "Cursor speed: %.1fx", speed)

    private fun parseHostPort(raw: String): Pair<String, Int> {
        val trimmed = raw.trim().removePrefix("ws://").removePrefix("http://").trimEnd('/')
        val parts = trimmed.split(":")
        val host = parts.getOrNull(0)?.trim().orEmpty()
        val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: Prefs.DEFAULT_PORT
        return host to port.coerceIn(1, 65535)
    }

    private fun dp(value: Int): Int = (value * dp).toInt()
}
