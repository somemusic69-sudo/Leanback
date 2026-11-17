package com.leanback.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var lanClient: LanClient

    private lateinit var rootLayout: LinearLayout
    private lateinit var touchpadView: TouchpadView
    private lateinit var mouseButtonsRow: LinearLayout
    private lateinit var leftStackedButtons: LinearLayout
    private lateinit var remoteImeView: RemoteImeView
    private lateinit var centerHint: TextView
    private lateinit var uploadBtn: ImageButton
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    private val activeModifiers = LinkedHashSet<String>()
    private val modifierViews = LinkedHashMap<String, TextView>()
    private var settingsDialog: SettingsDialog? = null
    private var keyboardUserHidden = false
    private val heldMouseButtons = LinkedHashSet<String>()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingUploadUri: Uri? = null

    private val clearFlashRunnable = Runnable {
        if (lanClient.getState() is ConnectionState.Connected) {
            statusText.visibility = View.GONE
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        if (lanClient.getState() is ConnectionState.Connected) {
            startImageUpload(uri)
        } else {
            pendingUploadUri = uri
            flashStatus("Waiting to send photo...", autoClear = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        prefs = Prefs(this)
        lanClient = LanClient(prefs) { state ->
            applyConnectionState(state)
        }

        setContentView(buildMainView())
        applyTouchpadConfig()
        bindListeners()
        applyConnectionState(lanClient.getState())
    }

    override fun onStart() {
        super.onStart()
        lanClient.reconnectIfSaved()
    }

    override fun onResume() {
        super.onResume()
        if (!keyboardUserHidden && settingsDialog?.isShowing != true) {
            remoteImeView.postDelayed({
                if (settingsDialog?.isShowing != true) {
                    remoteImeView.ensureKeyboardVisible(this)
                }
            }, 120L)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !keyboardUserHidden && settingsDialog?.isShowing != true) {
            remoteImeView.postDelayed({
                if (settingsDialog?.isShowing != true) {
                    remoteImeView.ensureKeyboardVisible(this)
                }
            }, 80L)
        }
    }

    override fun onStop() {
        super.onStop()
        // Release any held mouse buttons and close socket cleanly when backgrounded.
        for (btn in heldMouseButtons.toList()) {
            lanClient.sendMouseUp(btn)
        }
        heldMouseButtons.clear()
        populateMouseButtons()
        if (!isChangingConfigurations) {
            lanClient.disconnect(manual = false)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(clearFlashRunnable)
        uiScope.cancel()
        settingsDialog?.dismiss()
        settingsDialog = null
        super.onDestroy()
    }

    private fun buildMainView(): View {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            isMotionEventSplittingEnabled = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // Resize content smoothly above the active keyboard and below system status bar.
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val sysAndIme = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.updatePadding(
                left = sysAndIme.left,
                top = sysAndIme.top,
                right = sysAndIme.right,
                bottom = sysAndIme.bottom,
            )
            insets
        }

        // Top side: pure black touchpad + tiny unobtrusive top-right settings button
        val padFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isMotionEventSplittingEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        touchpadView = TouchpadView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        padFrame.addView(touchpadView)

        centerHint = TextView(this).apply {
            text = getString(R.string.hint_connect)
            setTextColor(Color.parseColor("#52525B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setOnClickListener { openSettings() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        padFrame.addView(centerHint)

        // Tiny top-right status + settings gear (no navbar, pure function)
        val topRight = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            )
        }

        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#52525B"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                rightMargin = dp(6)
            }
        }
        topRight.addView(statusDot)

        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(6) }
        }
        topRight.addView(statusText)

        topRight.setOnClickListener {
            val st = lanClient.getState()
            if (st is ConnectionState.Disconnected && !prefs.lastHost.isNullOrBlank()) {
                lanClient.connect(prefs.lastHost!!, prefs.lastPort, prefs.lastServerName, userInitiated = true)
            } else {
                openSettings()
            }
        }

        uploadBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_upload_image)
            contentDescription = getString(R.string.upload_image_cd)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener {
                val st = lanClient.getState()
                if (st is ConnectionState.Connected || st is ConnectionState.Connecting) {
                    imagePickerLauncher.launch("image/*")
                } else {
                    openSettings()
                }
            }
        }
        topRight.addView(uploadBtn)

        val settingsBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            contentDescription = getString(R.string.settings_cd)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener { openSettings() }
        }
        topRight.addView(settingsBtn)
        padFrame.addView(topRight)

        // Optional left-side stacked 1:1 Right-over-Left column floating at bottom-left
        leftStackedButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            isMotionEventSplittingEnabled = true
            setPadding(dp(6), dp(4), dp(4), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            )
        }
        padFrame.addView(leftStackedButtons)

        // Tiny focus anchor view so the system keyboard stays active at the bottom
        remoteImeView = RemoteImeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.START)
        }
        padFrame.addView(remoteImeView)
        rootLayout.addView(padFrame)

        // Optional side-by-side Left/Right click bar below touchpad
        mouseButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            isMotionEventSplittingEnabled = true
            setPadding(dp(6), dp(2), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50),
            )
        }
        populateMouseButtons()
        rootLayout.addView(mouseButtonsRow)

        // Subtle 1px divider above the PC quick-key strip
        rootLayout.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#18181B"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })

        // Slim dark PC-key strip directly above the always-active keyboard
        rootLayout.addView(buildQuickStrip())

        return rootLayout
    }

    private fun buildQuickStrip(): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#08080A"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38),
            )
        }

        val items = listOf(
            StripItem("Esc", "ESC", isModifier = false),
            StripItem("Tab", "TAB", isModifier = false),
            StripItem("Ctrl", "CTRL", isModifier = true),
            StripItem("Alt", "ALT", isModifier = true),
            StripItem("Win", "WIN", isModifier = true),
            StripItem("←", "LEFT", isModifier = false),
            StripItem("↓", "DOWN", isModifier = false),
            StripItem("↑", "UP", isModifier = false),
            StripItem("→", "RIGHT", isModifier = false),
        )

        for (item in items) {
            val btn = TextView(this).apply {
                text = item.label
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#A1A1AA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = makeStripKeyBg(active = false)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    leftMargin = dp(2)
                    rightMargin = dp(2)
                }
                setOnClickListener {
                    if (item.isModifier) {
                        toggleModifier(item.keyCode)
                    } else {
                        val mods = consumeModifiers()
                        lanClient.sendKey(item.keyCode, mods)
                        remoteImeView.resetBuffer()
                    }
                }
            }
            if (item.isModifier) {
                modifierViews[item.keyCode] = btn
            }
            strip.addView(btn)
        }

        val kbBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_keyboard)
            contentDescription = getString(R.string.keyboard_cd)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.MATCH_PARENT)
            setOnClickListener {
                val imeVisible = ViewCompat.getRootWindowInsets(rootLayout)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                if (imeVisible) {
                    keyboardUserHidden = true
                    remoteImeView.hideKeyboard(this@MainActivity)
                } else {
                    keyboardUserHidden = false
                    remoteImeView.ensureKeyboardVisible(this@MainActivity)
                }
            }
        }
        strip.addView(kbBtn)

        return strip
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeMouseClickButton(
        label: String,
        buttonName: String,
        params: LinearLayout.LayoutParams,
    ): TextView {
        fun applyStyle(pressed: Boolean, view: TextView) {
            view.setTextColor(
                if (pressed) Color.parseColor("#22D3EE") else Color.parseColor("#71717A")
            )
            view.background = GradientDrawable().apply {
                cornerRadius = 8f * resources.displayMetrics.density
                setColor(if (pressed) Color.parseColor("#11282F") else Color.parseColor("#0A0A0D"))
                setStroke(
                    1,
                    if (pressed) Color.parseColor("#22D3EE") else Color.parseColor("#1D1D22")
                )
            }
        }

        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            applyStyle(false, this)
            layoutParams = params
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (heldMouseButtons.add(buttonName)) {
                            applyStyle(true, v as TextView)
                            lanClient.sendMouseDown(buttonName)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (heldMouseButtons.remove(buttonName)) {
                            applyStyle(false, v as TextView)
                            lanClient.sendMouseUp(buttonName)
                            if (event.actionMasked == MotionEvent.ACTION_UP) {
                                remoteImeView.resetBuffer()
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun populateMouseButtons() {
        if (!::mouseButtonsRow.isInitialized || !::leftStackedButtons.isInitialized) return
        mouseButtonsRow.removeAllViews()
        leftStackedButtons.removeAllViews()

        mouseButtonsRow.addView(
            makeMouseClickButton(
                "Left",
                "left",
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    rightMargin = dp(6)
                }
            )
        )
        mouseButtonsRow.addView(
            makeMouseClickButton(
                "Right",
                "right",
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
        )

        val dm = resources.displayMetrics
        val squarePx = minOf(
            dp(120),
            (dm.widthPixels * 0.28f).toInt(),
            (dm.heightPixels * 0.145f).toInt(),
        ).coerceAtLeast(dp(84))

        leftStackedButtons.layoutParams = FrameLayout.LayoutParams(
            squarePx + dp(10),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        )

        leftStackedButtons.addView(
            makeMouseClickButton(
                "Right",
                "right",
                LinearLayout.LayoutParams(squarePx, squarePx).apply {
                    bottomMargin = dp(6)
                }
            )
        )
        leftStackedButtons.addView(
            makeMouseClickButton(
                "Left",
                "left",
                LinearLayout.LayoutParams(squarePx, squarePx)
            )
        )
    }

    private fun bindListeners() {
        touchpadView.listener = object : TouchpadView.Listener {
            override fun onMove(dxDp: Float, dyDp: Float) {
                lanClient.sendMove(dxDp, dyDp)
            }

            override fun onClick(button: String) {
                lanClient.sendClick(button)
                remoteImeView.resetBuffer()
            }

            override fun onMouseDown(button: String) {
                lanClient.sendMouseDown(button)
            }

            override fun onMouseUp(button: String) {
                lanClient.sendMouseUp(button)
            }

            override fun onScroll(dxDp: Float, dyDp: Float) {
                lanClient.sendScroll(dxDp, dyDp)
            }

            override fun onTouchInteract() {
                // Ensure IME stays focused without stealing attention unless closed.
            }
        }

        remoteImeView.inputListener = object : RemoteImeView.InputListener {
            override fun onText(text: String) {
                lanClient.sendText(text)
            }

            override fun onKey(key: String, repeat: Int) {
                val mods = consumeModifiers()
                lanClient.sendKey(key, mods, repeat)
            }

            override fun hasShortcutModifier(): Boolean = activeModifiers.isNotEmpty()

            override fun onShortcutText(text: String) {
                val mods = consumeModifiers()
                lanClient.sendText(text, mods)
            }
        }
    }

    private fun toggleModifier(mod: String) {
        if (activeModifiers.contains(mod)) {
            activeModifiers.remove(mod)
        } else {
            activeModifiers.add(mod)
        }
        refreshModifierUi()
    }

    private fun consumeModifiers(): List<String> {
        if (activeModifiers.isEmpty()) return emptyList()
        val copy = activeModifiers.toList()
        activeModifiers.clear()
        refreshModifierUi()
        return copy
    }

    private fun refreshModifierUi() {
        for ((mod, view) in modifierViews) {
            val active = activeModifiers.contains(mod)
            view.background = makeStripKeyBg(active)
            view.setTextColor(
                if (active) Color.parseColor("#22D3EE") else Color.parseColor("#A1A1AA")
            )
        }
    }

    private fun makeStripKeyBg(active: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 6f * resources.displayMetrics.density
        setColor(if (active) Color.parseColor("#11282F") else Color.parseColor("#111114"))
        if (active) {
            setStroke(1, Color.parseColor("#22D3EE"))
        }
    }

    private fun applyTouchpadConfig() {
        touchpadView.sensitivity = prefs.sensitivity
        touchpadView.naturalScroll = prefs.naturalScroll
        touchpadView.tapToClickEnabled = !prefs.buttonClickMode
        val showSideBySide = prefs.buttonClickMode && !prefs.buttonLayoutStacked
        val showLeftStacked = prefs.buttonClickMode && prefs.buttonLayoutStacked
        mouseButtonsRow.visibility = if (showSideBySide) View.VISIBLE else View.GONE
        leftStackedButtons.visibility = if (showLeftStacked) View.VISIBLE else View.GONE
        applyKeepScreenOn(lanClient.getState())
    }

    private fun applyConnectionState(state: ConnectionState) {
        settingsDialog?.updateState(state)
        applyKeepScreenOn(state)

        val dotDrawable = statusDot.background as? GradientDrawable
        uploadBtn.alpha = if (state is ConnectionState.Connected) 1f else 0.45f
        when (state) {
            is ConnectionState.Connected -> {
                dotDrawable?.setColor(Color.parseColor("#34D399"))
                centerHint.visibility = View.GONE
                val queued = pendingUploadUri
                if (queued != null) {
                    pendingUploadUri = null
                    startImageUpload(queued)
                } else {
                    mainHandler.removeCallbacks(clearFlashRunnable)
                    statusText.visibility = View.GONE
                }
            }

            is ConnectionState.PendingApproval -> {
                dotDrawable?.setColor(Color.parseColor("#FBBF24"))
                statusText.text = "Accept on PC (${state.code})"
                statusText.visibility = View.VISIBLE
                centerHint.visibility = View.GONE
            }

            is ConnectionState.Connecting -> {
                dotDrawable?.setColor(Color.parseColor("#FBBF24"))
                statusText.text = "Connecting..."
                statusText.visibility = View.VISIBLE
                centerHint.visibility = View.GONE
            }

            is ConnectionState.Disconnected -> {
                if (state.rejected) {
                    pendingUploadUri = null
                }
                dotDrawable?.setColor(
                    if (state.rejected) Color.parseColor("#F87171") else Color.parseColor("#52525B")
                )
                statusText.text = when {
                    state.rejected -> "Denied on PC"
                    prefs.lastHost.isNullOrBlank() -> ""
                    else -> "Offline"
                }
                statusText.visibility = if (statusText.text.isEmpty()) View.GONE else View.VISIBLE
                centerHint.visibility = if (prefs.lastHost.isNullOrBlank()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun startImageUpload(uri: Uri) {
        flashStatus("Sending photo...", autoClear = false)
        uiScope.launch {
            val result = lanClient.uploadImage(this@MainActivity, uri)
            result.onSuccess { msg ->
                flashStatus(msg, autoClear = true)
            }.onFailure { err ->
                flashStatus(err.message ?: "Photo upload failed", autoClear = true)
            }
        }
    }

    private fun flashStatus(message: String, autoClear: Boolean) {
        mainHandler.removeCallbacks(clearFlashRunnable)
        statusText.text = message
        statusText.visibility = View.VISIBLE
        if (autoClear) {
            mainHandler.postDelayed(clearFlashRunnable, 2600L)
        }
    }

    private fun applyKeepScreenOn(state: ConnectionState) {
        if (prefs.keepScreenOn && state is ConnectionState.Connected) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun openSettings() {
        if (settingsDialog?.isShowing == true) return
        settingsDialog = SettingsDialog(
            context = this,
            prefs = prefs,
            lanClient = lanClient,
            onTouchpadConfigChanged = { applyTouchpadConfig() },
            onClosed = {
                settingsDialog = null
                remoteImeView.postDelayed({
                    remoteImeView.ensureKeyboardVisible(this)
                }, 100L)
            },
        ).also { it.show() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class StripItem(val label: String, val keyCode: String, val isModifier: Boolean)
}
