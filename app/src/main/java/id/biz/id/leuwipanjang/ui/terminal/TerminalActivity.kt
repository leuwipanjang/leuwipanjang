package id.biz.id.leuwipanjang.ui.terminal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.content.ClipData
import android.content.ClipboardManager
import android.view.ContextMenu
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import id.biz.id.leuwipanjang.R
import id.biz.id.leuwipanjang.data.model.AuthType
import id.biz.id.leuwipanjang.data.model.SshHost
import id.biz.id.leuwipanjang.data.model.SshKey
import id.biz.id.leuwipanjang.data.model.TerminalTheme
import id.biz.id.leuwipanjang.data.repository.HostRepository
import id.biz.id.leuwipanjang.data.repository.KeyRepository
import id.biz.id.leuwipanjang.data.repository.SnippetRepository
import id.biz.id.leuwipanjang.databinding.ActivityTerminalBinding
import id.biz.id.leuwipanjang.databinding.DialogFontSizeBinding
import androidx.activity.result.contract.ActivityResultContracts
import id.biz.id.leuwipanjang.ssh.SessionRegistry
import id.biz.id.leuwipanjang.ssh.SshConnectionListener
import id.biz.id.leuwipanjang.ssh.SshManager
import id.biz.id.leuwipanjang.utils.AnsiParser
import id.biz.id.leuwipanjang.utils.isTv
import id.biz.id.leuwipanjang.utils.setupEdgeToEdge

class TerminalActivity : AppCompatActivity(), SshConnectionListener {

    companion object {
        const val EXTRA_HOST_ID = "extra_host_id"
        private const val MAX_PENDING_OUTPUT = 512 * 1024
    }

    private lateinit var binding: ActivityTerminalBinding
    private val isTv by lazy { isTv() }
    private lateinit var hostRepository: HostRepository
    private lateinit var keyRepository: KeyRepository
    private lateinit var snippetRepository: SnippetRepository

    private var host: SshHost? = null
    private var sshKey: SshKey? = null
    private var sshManager: SshManager? = null

    private lateinit var ansiParser: AnsiParser
    private lateinit var activeTheme: TerminalTheme

    private var isCtrlActive = false
    private var isAltActive = false
    private var isKeyboardOpen = false
    private var currentFontSize = 6
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    /** True while replaying a re-attached session's output, so its queries aren't answered twice. */
    private var isReplaying = false

    // Android 13+: the "session active" notification needs permission. Sessions keep
    // running without it; the notification is just hidden.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Predictive back (default on targetSdk 36) no longer calls onBackPressed()
        onBackPressedDispatcher.addCallback(this) {
            // On TV the D-pad drives the terminal, so the first Back moves focus
            // out of the terminal to the key bar; Back again exits.
            if (isSelecting) {
                exitSelectionMode()
            } else if (isTv && binding.etTerminalInput.hasFocus()) {
                binding.keyEsc.requestFocus()
                Toast.makeText(this@TerminalActivity, R.string.tv_terminal_keybar_hint, Toast.LENGTH_SHORT).show()
            } else {
                handleExit()
            }
        }

        hostRepository = HostRepository(this)
        keyRepository = KeyRepository(this)
        snippetRepository = SnippetRepository(this)

        val hostId = intent.getStringExtra(EXTRA_HOST_ID)
        host = hostId?.let { hostRepository.getById(it) }

        if (host == null) {
            Toast.makeText(this, "Host configuration not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val targetHost = host!!
        hostRepository.updateLastConnected(targetHost.id)

        if (targetHost.authType == AuthType.KEY) {
            sshKey = targetHost.keyId?.let { keyRepository.getById(it) }
        }

        setupThemeAndUi(targetHost)
        setupAccessoryKeys()
        setupInputHandlers()
        setupTvNavigation()

        startSshConnection(targetHost)
    }

    private fun setupThemeAndUi(targetHost: SshHost) {
        activeTheme = TerminalTheme.findById(targetHost.themeId)
        binding.tvTerminalOutput.setText("", android.widget.TextView.BufferType.EDITABLE)
        ansiParser = AnsiParser(activeTheme).apply {
            onTitleChanged = { title ->
                binding.tvTerminalSubtitle.text = title
            }
            onClearScreen = {
                binding.tvTerminalOutput.editableText?.clear() ?: run {
                    binding.tvTerminalOutput.text = ""
                }
            }
            onSendTerminalResponse = { response ->
                if (!isReplaying) sshManager?.send(response)
            }
        }

        currentFontSize = if (targetHost.fontSize in 1..8) targetHost.fontSize else 6
        binding.tvTerminalTitle.text = targetHost.alias
        binding.tvTerminalSubtitle.text = "${targetHost.username}@${targetHost.hostname}:${targetHost.port}"

        // Apply theme styling to terminal view
        binding.root.setBackgroundColor(activeTheme.backgroundColor)
        binding.scrollTerminal.setBackgroundColor(activeTheme.backgroundColor)
        binding.tvTerminalOutput.setBackgroundColor(activeTheme.backgroundColor)
        binding.tvTerminalOutput.setTextColor(activeTheme.foregroundColor)
        binding.tvTerminalOutput.typeface = Typeface.MONOSPACE
        binding.tvTerminalOutput.setTextSize(TypedValue.COMPLEX_UNIT_PT, currentFontSize.toFloat())

        binding.btnBack.setOnClickListener { handleExit() }
        binding.btnDisconnect.setOnClickListener { handleExit() }
        binding.btnRunSnippet.setOnClickListener { showSnippetRunnerDialog() }
        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
        binding.btnToggleKeyboard.setOnClickListener { toggleKeyboard() }
        binding.btnFontSize.setOnClickListener { showFontSizeDialog() }
    }

    /** Re-attaches to this host's running session if there is one, else opens a new one. */
    private fun startSshConnection(targetHost: SshHost) {
        val existing = SessionRegistry.get(targetHost.id)
        if (existing != null) {
            sshManager = existing
            val history = existing.attach(this)
            if (history.isNotEmpty()) {
                isReplaying = true
                try {
                    onDataReceived(history)
                } finally {
                    isReplaying = false
                }
            }
            // A toast, not an appended line: text written around the ANSI parser would put
            // the next output on the wrong line (or inside a full-screen app's screen).
            Toast.makeText(this, R.string.terminal_reattached, Toast.LENGTH_SHORT).show()
            binding.viewStatusDot.setBackgroundColor(
                Color.parseColor(if (existing.isConnected) "#10B981" else "#F59E0B")
            )
            scrollToBottom()
            return
        }

        requestNotificationPermission()
        sshManager = SessionRegistry.open(this, targetHost, sshKey).also { it.attach(this) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupAccessoryKeys() {
        binding.keyEsc.setOnClickListener { sendSpecialKey("\u001B") }
        binding.keyTab.setOnClickListener { sendSpecialKey("\t") }
        binding.keyShiftTab.setOnClickListener { sendSpecialKey("\u001B[Z") } // Shift+Tab (Backtab)

        binding.keyCtrl.setOnClickListener {
            isCtrlActive = !isCtrlActive
            updateModifierButtonState(binding.keyCtrl, isCtrlActive)
            binding.etTerminalInput.requestFocus()
        }

        binding.keyAlt.setOnClickListener {
            isAltActive = !isAltActive
            updateModifierButtonState(binding.keyAlt, isAltActive)
            binding.etTerminalInput.requestFocus()
        }

        // Arrow keys — send Application mode (\eOX) if DECCKM active, else Normal mode (\e[X)
        binding.keyUp.setOnClickListener {
            sendSpecialKey(if (ansiParser.isApplicationCursorKeysActive) "\u001BOA" else "\u001B[A")
        }
        binding.keyDown.setOnClickListener {
            sendSpecialKey(if (ansiParser.isApplicationCursorKeysActive) "\u001BOB" else "\u001B[B")
        }
        binding.keyLeft.setOnClickListener {
            sendSpecialKey(if (ansiParser.isApplicationCursorKeysActive) "\u001BOD" else "\u001B[D")
        }
        binding.keyRight.setOnClickListener {
            sendSpecialKey(if (ansiParser.isApplicationCursorKeysActive) "\u001BOC" else "\u001B[C")
        }

        // Page Up / Page Down (for scrolling in Claude Code, nano, less, vim, etc.)
        binding.keyPgUp.setOnClickListener {
            sendSpecialKey("\u001B[5~")
        }
        binding.keyPgDown.setOnClickListener {
            sendSpecialKey("\u001B[6~")
        }

        // Symbols
        binding.keyPipe.setOnClickListener { sendSpecialKey("|") }
        binding.keySlash.setOnClickListener { sendSpecialKey("/") }
        binding.keyTilde.setOnClickListener { sendSpecialKey("~") }
        binding.keyDash.setOnClickListener { sendSpecialKey("-") }
        binding.keyColon.setOnClickListener { sendSpecialKey(":") }

        // Ctrl shortcuts
        binding.keyCtrlC.setOnClickListener { sendSpecialKey("\u0003") } // SIGINT (^C)
        binding.keyCtrlD.setOnClickListener { sendSpecialKey("\u0004") } // EOF (^D)
        binding.keyCtrlBackslash.setOnClickListener { sendSpecialKey("\u001C") } // SIGQUIT (^\ / Ctrl+\)
        binding.keyCtrlZ.setOnClickListener { sendSpecialKey("\u001A") } // SIGTSTP (^Z / Ctrl+Z)
    }

    private fun updateModifierButtonState(button: View, isActive: Boolean) {
        val color = if (isActive) Color.parseColor("#38BDF8") else Color.parseColor("#1E293B")
        button.setBackgroundColor(color)
    }

    private fun sendSpecialKey(chars: String) {
        binding.etTerminalInput.resetComposing()
        var finalChars = chars
        if (isCtrlActive && chars.length == 1) {
            val ctrlChar = TerminalInputView.controlCharFor(chars[0])
            if (ctrlChar != null) {
                finalChars = ctrlChar.toString()
            }
            isCtrlActive = false
            updateModifierButtonState(binding.keyCtrl, false)
        } else if (isAltActive) {
            finalChars = "\u001B$chars"
            isAltActive = false
            updateModifierButtonState(binding.keyAlt, false)
        }
        sshManager?.send(finalChars)
        binding.etTerminalInput.requestFocus()
    }

    /**
     * Android TV: OK on the remote opens the on-screen keyboard; the accessory key bar
     * becomes reachable with the D-pad (it is not focusable on touch devices so it never
     * steals focus from the terminal input there).
     */
    private fun setupTvNavigation() {
        binding.etTerminalInput.onCenterPressed = { showKeyboard() }
        // ScrollView re-enables focusable in its constructor (overriding the XML), which lets it
        // grab D-pad / hardware-keyboard focus away from the terminal input.
        binding.scrollTerminal.isFocusable = false
        binding.etTerminalInput.requestFocus()
        if (!isTv) return
        for (i in 0 until binding.layoutAccessoryKeys.childCount) {
            binding.layoutAccessoryKeys.getChildAt(i).isFocusable = true
        }
        Toast.makeText(this, R.string.tv_terminal_start_hint, Toast.LENGTH_LONG).show()
    }

    private fun setupInputHandlers() {
        // Pinch-to-zoom gesture detector for terminal font size
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var baseSize = currentFontSize.toFloat()

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                baseSize = currentFontSize.toFloat()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val newSize = (baseSize * factor).toInt().coerceIn(1, 8)
                if (newSize != currentFontSize) {
                    applyFontSize(newSize)
                }
                return false
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                saveFontSize()
            }
        })

        binding.scrollTerminal.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            false
        }

        // Tap on terminal output to open keyboard
        binding.tvTerminalOutput.setOnClickListener {
            showKeyboard()
        }

        // Long-press (touch) and right-click (mouse, e.g. Samsung DeX) open Paste / Copy all
        registerForContextMenu(binding.tvTerminalOutput)
        setupPointerHandling()
        setupSelectionMode()

        binding.scrollTerminal.setOnClickListener {
            showKeyboard()
        }

        // Keep keyboard toggle button state synced with IME visibility
        // (also pads the layout for system bars + IME, since the window is edge-to-edge)
        setupEdgeToEdge(binding.root) { insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            isKeyboardOpen = imeVisible
            updateKeyboardToggleButton(imeVisible)
        }

        binding.scrollTerminal.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTerminalSize()
        }

        // Auto-focus the hidden input view on start
        binding.etTerminalInput.requestFocus()

        // Setup direct terminal input listener
        binding.etTerminalInput.onCommitTextInput = { text ->
            sendUserInput(text)
        }
        binding.etTerminalInput.onBackspace = {
            sendUserInput("\u007F") // DEL / Backspace
        }
        binding.etTerminalInput.onPaste = { pasteFromClipboard() }
        binding.etTerminalInput.onEnter = {
            sendUserInput("\r")
        }
        // Route arrow keys through DECCKM-aware resolver
        binding.etTerminalInput.getArrowKey = { direction ->
            if (ansiParser.isApplicationCursorKeysActive) "\u001BO$direction" else "\u001B[$direction"
        }
    }

    private fun toggleKeyboard() {
        val rootInsets = ViewCompat.getRootWindowInsets(binding.root)
        val isVisible = rootInsets?.isVisible(WindowInsetsCompat.Type.ime()) ?: isKeyboardOpen
        if (isVisible) {
            hideKeyboard()
        } else {
            showKeyboard()
        }
    }

    private fun showKeyboard() {
        binding.etTerminalInput.requestFocus()
        binding.etTerminalInput.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etTerminalInput, 0)
            val insetsController = WindowCompat.getInsetsController(window, binding.etTerminalInput)
            insetsController.show(WindowInsetsCompat.Type.ime())
        }
        isKeyboardOpen = true
        updateKeyboardToggleButton(true)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val token = binding.etTerminalInput.windowToken ?: binding.root.windowToken
        imm?.hideSoftInputFromWindow(token, 0)
        val insetsController = WindowCompat.getInsetsController(window, binding.root)
        insetsController.hide(WindowInsetsCompat.Type.ime())
        isKeyboardOpen = false
        updateKeyboardToggleButton(false)
    }

    private fun updateKeyboardToggleButton(isOpen: Boolean) {
        if (isOpen) {
            binding.btnToggleKeyboard.setImageResource(R.drawable.ic_keyboard_hide)
            binding.btnToggleKeyboard.setColorFilter(
                ContextCompat.getColor(this, R.color.primary)
            )
            binding.btnToggleKeyboard.contentDescription = getString(R.string.hide_keyboard)
        } else {
            binding.btnToggleKeyboard.setImageResource(R.drawable.ic_keyboard)
            binding.btnToggleKeyboard.setColorFilter(
                ContextCompat.getColor(this, R.color.text_primary)
            )
            binding.btnToggleKeyboard.contentDescription = getString(R.string.show_keyboard)
        }
    }

    private fun applyFontSize(size: Int) {
        currentFontSize = size.coerceIn(1, 8)
        binding.tvTerminalOutput.setTextSize(TypedValue.COMPLEX_UNIT_PT, currentFontSize.toFloat())
        binding.tvTerminalOutput.post { updateTerminalSize() }
    }

    private var lastPtyCols = 0
    private var lastPtyRows = 0

    private fun updateTerminalSize() {
        val viewport = binding.scrollTerminal
        val tv = binding.tvTerminalOutput
        val width = viewport.width - viewport.paddingLeft - viewport.paddingRight - tv.paddingLeft - tv.paddingRight
        val height = viewport.height - viewport.paddingTop - viewport.paddingBottom - tv.paddingTop - tv.paddingBottom

        val paint = tv.paint
        // Measure widest character among 'M', 'W', and horizontal line '─' (U+2500)
        // to prevent border characters from wrapping into a second line
        val charWidth = maxOf(
            paint.measureText("M"),
            paint.measureText("W"),
            paint.measureText("─")
        )
        val charHeight = tv.lineHeight.toFloat()

        if (width > 0 && height > 0 && charWidth > 0 && charHeight > 0) {
            // Apply a small safety margin (4px) to guard against subpixel antialiasing/rounding
            val usableWidth = (width - 4).coerceAtLeast(0)
            val cols = (usableWidth / charWidth).toInt().coerceIn(20, 240)
            val rows = (height / charHeight).toInt().coerceIn(10, 100)
            if (cols != lastPtyCols || rows != lastPtyRows) {
                lastPtyCols = cols
                lastPtyRows = rows
                ansiParser.setTerminalDimensions(cols, rows)
                sshManager?.updatePtySize(cols, rows)
            }
        }
    }

    private fun saveFontSize() {
        host?.let {
            if (it.fontSize != currentFontSize) {
                it.fontSize = currentFontSize
                hostRepository.save(it)
            }
        }
    }

    private fun showFontSizeDialog() {
        val dialogBinding = DialogFontSizeBinding.inflate(layoutInflater)
        dialogBinding.tvFontSizeValue.text = "$currentFontSize pt"
        dialogBinding.sliderFontSize.value = currentFontSize.toFloat().coerceIn(1f, 8f)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun updateDialogUi(size: Int) {
            val clamped = size.coerceIn(1, 8)
            applyFontSize(clamped)
            dialogBinding.tvFontSizeValue.text = "$clamped pt"
            if (dialogBinding.sliderFontSize.value != clamped.toFloat()) {
                dialogBinding.sliderFontSize.value = clamped.toFloat()
            }
        }

        dialogBinding.btnFontMinus.setOnClickListener {
            if (currentFontSize > 1) {
                updateDialogUi(currentFontSize - 1)
            }
        }

        dialogBinding.btnFontPlus.setOnClickListener {
            if (currentFontSize < 8) {
                updateDialogUi(currentFontSize + 1)
            }
        }

        dialogBinding.sliderFontSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val size = value.toInt()
                if (size != currentFontSize) {
                    applyFontSize(size)
                    dialogBinding.tvFontSizeValue.text = "$size pt"
                }
            }
        }

        dialogBinding.btnFontReset.setOnClickListener {
            updateDialogUi(6)
        }

        dialogBinding.btnFontDone.setOnClickListener {
            saveFontSize()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            saveFontSize()
        }

        dialog.show()
    }

    private fun sendUserInput(input: String) {
        if (input.isEmpty()) return

        var toSend = input
        if (isCtrlActive) {
            val ctrlChar = TerminalInputView.controlCharFor(input[0])
            if (ctrlChar != null) {
                toSend = ctrlChar.toString() + input.substring(1)
            }
            isCtrlActive = false
            updateModifierButtonState(binding.keyCtrl, false)
        } else if (isAltActive) {
            toSend = "\u001B$input"
            isAltActive = false
            updateModifierButtonState(binding.keyAlt, false)
        }

        sshManager?.send(toSend)
        if (!binding.etTerminalInput.hasFocus()) {
            binding.etTerminalInput.requestFocus()
        }
    }

    /**
     * Mouse and touch handling on the terminal output:
     * - Right-click: Android opens a view's context menu as soon as the right button goes
     *   down, and with a mouse (Samsung DeX, Chromebooks) that menu then ignored clicks on its
     *   items, so it is opened once the button is released instead.
     * - Left-button drag: enters text selection mode and hands the drag to the selectable
     *   overlay, so text can be selected the way it is on a desktop terminal.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupPointerHandling() {
        val output = binding.tvTerminalOutput
        val overlay = binding.tvTerminalSelection
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var rightPressed = false
        var leftPressed = false
        var forwarding = false
        var startX = 0f
        var startY = 0f

        output.setOnTouchListener { view, event ->
            val isMouse = event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rightPressed = isMouse && event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)
                    leftPressed = isMouse && event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)
                    forwarding = false
                    startX = event.x
                    startY = event.y
                    // A mouse drag selects text rather than scrolling (the wheel scrolls).
                    if (leftPressed) view.parent?.requestDisallowInterceptTouchEvent(true)
                    rightPressed
                }
                MotionEvent.ACTION_MOVE -> when {
                    rightPressed -> true
                    forwarding -> {
                        overlay.dispatchTouchEvent(event)
                        true
                    }
                    leftPressed && Math.hypot(
                        (event.x - startX).toDouble(), (event.y - startY).toDouble()
                    ) > touchSlop -> {
                        view.cancelLongPress()
                        view.isPressed = false
                        enterSelectionMode()
                        forwarding = true
                        val down = MotionEvent.obtain(event).apply {
                            action = MotionEvent.ACTION_DOWN
                            setLocation(startX, startY)
                        }
                        overlay.dispatchTouchEvent(down)
                        down.recycle()
                        overlay.dispatchTouchEvent(event)
                        true
                    }
                    else -> false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val consumed = rightPressed || forwarding
                    if (forwarding) overlay.dispatchTouchEvent(event)
                    if (rightPressed && event.actionMasked == MotionEvent.ACTION_UP) {
                        val x = event.x
                        val y = event.y
                        view.post { view.showContextMenu(x, y) }
                    }
                    rightPressed = false
                    leftPressed = false
                    forwarding = false
                    consumed
                }
                else -> rightPressed || forwarding
            }
        }

        // Same deferred right-click on the selection overlay (Copy / Select all / Done).
        var overlayRightPressed = false
        overlay.setOnTouchListener { view, event ->
            val isRight = event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) &&
                event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    overlayRightPressed = isRight
                    overlayRightPressed
                }
                MotionEvent.ACTION_UP -> {
                    if (overlayRightPressed) {
                        overlayRightPressed = false
                        val x = event.x
                        val y = event.y
                        view.post {
                            overlayMenuRequested = true
                            view.showContextMenu(x, y)
                            overlayMenuRequested = false
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    val consumed = overlayRightPressed
                    overlayRightPressed = false
                    consumed
                }
                else -> overlayRightPressed
            }
        }
        registerForContextMenu(overlay)
    }

    // ---- Text selection mode ----
    // The live output keeps changing, so selecting happens on a frozen, selectable copy laid
    // over it. Output arriving meanwhile is held back and shown when selection mode ends.

    private var isSelecting = false
    private val pendingOutput = StringBuilder()
    private var overlayMenuRequested = false

    private fun setupSelectionMode() {
        val overlay = binding.tvTerminalSelection
        binding.btnSelectionCopy.setOnClickListener { copySelection() }
        binding.btnSelectionDone.setOnClickListener { exitSelectionMode() }

        overlay.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: Menu) = true
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: MenuItem): Boolean {
                if (item.itemId == android.R.id.copy) {
                    copySelection()
                    mode.finish()
                    return true
                }
                return false
            }
            override fun onDestroyActionMode(mode: android.view.ActionMode) {
                // Tapping away clears the selection; that also ends selection mode.
                overlay.post { if (isSelecting && !overlay.hasSelection()) exitSelectionMode() }
            }
        }

        overlay.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when {
                keyCode == KeyEvent.KEYCODE_ESCAPE -> {
                    exitSelectionMode()
                    true
                }
                keyCode == KeyEvent.KEYCODE_C && event.isCtrlPressed -> {
                    copySelection()
                    true
                }
                else -> false
            }
        }
    }

    private fun enterSelectionMode() {
        if (isSelecting) return
        isSelecting = true
        val overlay = binding.tvTerminalSelection
        overlay.setTextSize(TypedValue.COMPLEX_UNIT_PT, currentFontSize.toFloat())
        overlay.typeface = Typeface.MONOSPACE
        overlay.setTextColor(activeTheme.foregroundColor)
        overlay.setBackgroundColor(activeTheme.backgroundColor)
        overlay.text = android.text.SpannableString(binding.tvTerminalOutput.text)
        overlay.visibility = View.VISIBLE
        binding.tvTerminalOutput.visibility = View.INVISIBLE
        // The bar takes the key bar's place (same height), so the terminal is not resized.
        binding.scrollAccessoryKeys.visibility = View.GONE
        binding.layoutSelectionBar.visibility = View.VISIBLE
        overlay.requestFocus()
    }

    private fun exitSelectionMode() {
        if (!isSelecting) return
        isSelecting = false
        val overlay = binding.tvTerminalSelection
        overlay.clearFocus()
        overlay.visibility = View.INVISIBLE
        overlay.text = ""
        binding.tvTerminalOutput.visibility = View.VISIBLE
        binding.layoutSelectionBar.visibility = View.GONE
        binding.scrollAccessoryKeys.visibility = View.VISIBLE
        binding.etTerminalInput.requestFocus()
        if (pendingOutput.isNotEmpty()) {
            val held = pendingOutput.toString()
            pendingOutput.setLength(0)
            onDataReceived(held)
        }
        scrollToBottom()
    }

    private fun copySelection() {
        val overlay = binding.tvTerminalSelection
        val start = minOf(overlay.selectionStart, overlay.selectionEnd)
        val end = maxOf(overlay.selectionStart, overlay.selectionEnd)
        if (start < 0 || start == end) {
            Toast.makeText(this, R.string.terminal_nothing_selected, Toast.LENGTH_SHORT).show()
            return
        }
        // Full-screen apps pad lines with spaces; drop them from each copied line.
        val text = overlay.text.substring(start, end).lines().joinToString("\n") { it.trimEnd() }
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Terminal Selection", text))
        Toast.makeText(this, R.string.terminal_selection_copied, Toast.LENGTH_SHORT).show()
        exitSelectionMode()
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        // On a mouse right-click a TextView first adds its own editor items (Undo, Cut, Copy,
        // Paste...). On the read-only output they do nothing and the menu stays open, so
        // only the terminal's own actions are shown.
        menu.clear()
        if (v === binding.tvTerminalSelection) {
            // Long-press on the selectable text must fall through to Android's own word
            // selection, so this menu only appears for a right-click.
            if (!overlayMenuRequested) return
            menu.add(Menu.NONE, R.id.menu_terminal_copy_selection, 0, R.string.terminal_copy)
                .isEnabled = binding.tvTerminalSelection.hasSelection()
            menu.add(Menu.NONE, R.id.menu_terminal_select_all, 1, android.R.string.selectAll)
            menu.add(Menu.NONE, R.id.menu_terminal_selection_done, 2, R.string.terminal_selection_done)
            return
        }
        menu.add(Menu.NONE, R.id.menu_terminal_paste, 0, R.string.terminal_paste)
        menu.add(Menu.NONE, R.id.menu_terminal_select_text, 1, R.string.terminal_select_text)
        menu.add(Menu.NONE, R.id.menu_terminal_copy_all, 2, R.string.terminal_copy_all)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_terminal_paste -> {
            pasteFromClipboard()
            true
        }
        R.id.menu_terminal_copy_all -> {
            copyAllOutput()
            true
        }
        R.id.menu_terminal_select_text -> {
            // Android's own long-press / drag on the frozen copy then selects, with handles
            // and a Copy toolbar.
            enterSelectionMode()
            true
        }
        R.id.menu_terminal_copy_selection -> {
            copySelection()
            true
        }
        R.id.menu_terminal_select_all -> {
            val text = binding.tvTerminalSelection.text
            if (text is android.text.Spannable) android.text.Selection.setSelection(text, 0, text.length)
            true
        }
        R.id.menu_terminal_selection_done -> {
            exitSelectionMode()
            true
        }
        else -> super.onContextItemSelected(item)
    }

    private fun copyAllOutput() {
        val text = binding.tvTerminalOutput.text.toString()
        if (text.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Terminal Output", text))
        Toast.makeText(this, R.string.terminal_output_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Sends the clipboard text to the session as typed input. Newlines become CR (what the
     * Enter key sends). If the remote program enabled bracketed paste the text is wrapped so
     * it is inserted rather than executed; otherwise multi-line text asks for confirmation,
     * because each line would run as a command.
     */
    private fun pasteFromClipboard() {
        val session = sshManager
        if (session == null || !session.isConnected) {
            Toast.makeText(this, R.string.terminal_paste_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val raw = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (raw.isNullOrEmpty()) {
            Toast.makeText(this, R.string.terminal_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val text = raw.replace("\r\n", "\r").replace('\n', '\r')

        if (ansiParser.isBracketedPasteActive) {
            // Drop any end marker inside the text so it cannot break out of the paste.
            val safe = text.replace("\u001B[201~", "")
            session.send("\u001B[200~$safe\u001B[201~")
            return
        }

        val lineCount = text.trimEnd('\r').count { it == '\r' } + 1
        if (!text.contains('\r')) {
            session.send(text)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.terminal_paste)
            .setMessage(resources.getQuantityString(R.plurals.terminal_paste_confirm, lineCount, lineCount))
            .setPositiveButton(R.string.terminal_paste) { _, _ -> session.send(text) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSnippetRunnerDialog() {
        val snippets = snippetRepository.getAll()
        if (snippets.isEmpty()) {
            Toast.makeText(this, "No snippets available", Toast.LENGTH_SHORT).show()
            return
        }

        val titles = snippets.map { "${it.title}  (${it.command.trim()})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Execute Snippet")
            .setItems(titles) { _, which ->
                val chosen = snippets[which]
                sshManager?.send(chosen.command)
                Toast.makeText(this, "Executing '${chosen.title}'", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // SSH Connection Callbacks
    override fun onConnecting() {
        binding.viewStatusDot.setBackgroundColor(Color.parseColor("#F59E0B")) // Warning Yellow
        binding.tvTerminalOutput.append("\n[Connecting to ${host?.alias} (${host?.hostname}:${host?.port})...]\n")
        scrollToBottom()
    }

    override fun onConnected() {
        binding.viewStatusDot.setBackgroundColor(Color.parseColor("#10B981")) // Green Success
        binding.tvTerminalOutput.append("[Connected via ${if (host?.authType == AuthType.KEY) "Public Key" else "Password"}]\n\n")
        binding.tvTerminalOutput.post { updateTerminalSize() }
        scrollToBottom()
    }

    override fun onDataReceived(text: String) {
        if (isSelecting) {
            pendingOutput.append(text)
            // Don't hold back an endless stream (e.g. tail -f) while the user selects.
            if (pendingOutput.length > MAX_PENDING_OUTPUT) exitSelectionMode()
            return
        }
        val editable = binding.tvTerminalOutput.editableText
        if (editable != null) {
            ansiParser.appendTo(editable, text)
        } else {
            val styled = ansiParser.parse(text)
            binding.tvTerminalOutput.append(styled)
        }
        if (ansiParser.isAlternateScreenActive) {
            binding.scrollTerminal.post {
                binding.scrollTerminal.scrollTo(0, 0)
            }
        } else {
            scrollToBottom()
        }
    }

    override fun onError(errorMessage: String) {
        binding.viewStatusDot.setBackgroundColor(Color.parseColor("#EF4444")) // Red Error
        binding.tvTerminalOutput.append("\n\n[SSH Connection Error: $errorMessage]\n")
        if (host?.authType == AuthType.KEY) {
            binding.tvTerminalOutput.append("[Tip: Ensure this host's public key is appended to ~/.ssh/authorized_keys on the server]\n")
        }
        scrollToBottom()
    }

    override fun onDisconnected() {
        binding.viewStatusDot.setBackgroundColor(Color.parseColor("#64748B")) // Grey
        binding.tvTerminalOutput.append("\n[Session disconnected]\n")
        scrollToBottom()
    }

    private fun scrollToBottom() {
        if (isSelecting) return
        binding.scrollTerminal.post {
            binding.scrollTerminal.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun handleExit() {
        val session = sshManager
        if (session?.isConnected == true) {
            AlertDialog.Builder(this)
                .setTitle(R.string.terminal_disconnect)
                .setMessage(R.string.confirm_disconnect)
                .setPositiveButton(R.string.terminal_disconnect) { _, _ ->
                    SessionRegistry.close(session)
                    finish()
                }
                .setNeutralButton(R.string.terminal_keep_in_background) { _, _ -> finish() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            // Still connecting (or already gone): leaving abandons the attempt.
            session?.let { SessionRegistry.close(it) }
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The session belongs to SessionRegistry and keeps running (e.g. the app went to the
        // background or the activity is being recreated); this screen just stops listening.
        sshManager?.detach(this)
    }
}
