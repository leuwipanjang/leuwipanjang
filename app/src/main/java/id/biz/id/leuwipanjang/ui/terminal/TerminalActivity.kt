package id.biz.id.leuwipanjang.ui.terminal

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import id.biz.id.leuwipanjang.ssh.SshConnectionListener
import id.biz.id.leuwipanjang.ssh.SshManager
import id.biz.id.leuwipanjang.utils.AnsiParser

class TerminalActivity : AppCompatActivity(), SshConnectionListener {

    companion object {
        const val EXTRA_HOST_ID = "extra_host_id"
    }

    private lateinit var binding: ActivityTerminalBinding
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                sshManager?.send(response)
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
        binding.btnToggleKeyboard.setOnClickListener { toggleKeyboard() }
        binding.btnFontSize.setOnClickListener { showFontSizeDialog() }
    }

    private fun startSshConnection(targetHost: SshHost) {
        sshManager = SshManager(targetHost, sshKey, this)
        sshManager?.connect()
    }

    private fun setupAccessoryKeys() {
        binding.keyEsc.setOnClickListener { sendSpecialKey("\u001B") }
        binding.keyTab.setOnClickListener { sendSpecialKey("\t") }

        binding.keyCtrl.setOnClickListener {
            isCtrlActive = !isCtrlActive
            updateModifierButtonState(binding.keyCtrl, isCtrlActive)
        }

        binding.keyAlt.setOnClickListener {
            isAltActive = !isAltActive
            updateModifierButtonState(binding.keyAlt, isAltActive)
        }

        // Arrow keys ANSI escape codes
        binding.keyUp.setOnClickListener { sendSpecialKey("\u001B[A") }
        binding.keyDown.setOnClickListener { sendSpecialKey("\u001B[B") }
        binding.keyLeft.setOnClickListener { sendSpecialKey("\u001B[D") }
        binding.keyRight.setOnClickListener { sendSpecialKey("\u001B[C") }

        // Symbols
        binding.keyPipe.setOnClickListener { sendSpecialKey("|") }
        binding.keySlash.setOnClickListener { sendSpecialKey("/") }
        binding.keyTilde.setOnClickListener { sendSpecialKey("~") }
        binding.keyDash.setOnClickListener { sendSpecialKey("-") }
        binding.keyColon.setOnClickListener { sendSpecialKey(":") }

        // Ctrl shortcuts
        binding.keyCtrlC.setOnClickListener { sendSpecialKey("\u0003") } // SIGINT
        binding.keyCtrlD.setOnClickListener { sendSpecialKey("\u0004") } // EOF
    }

    private fun updateModifierButtonState(button: View, isActive: Boolean) {
        val color = if (isActive) Color.parseColor("#38BDF8") else Color.parseColor("#1E293B")
        button.setBackgroundColor(color)
    }

    private fun sendSpecialKey(chars: String) {
        binding.etTerminalInput.resetComposing()
        var finalChars = chars
        if (isCtrlActive && chars.length == 1) {
            val c = chars[0]
            if (c in 'a'..'z' || c in 'A'..'Z') {
                val ctrlCode = (c.uppercaseChar().code - 64).toChar()
                finalChars = ctrlCode.toString()
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

        binding.tvTerminalOutput.setOnLongClickListener {
            val text = binding.tvTerminalOutput.text.toString()
            if (text.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Terminal Output", text)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this, "Terminal output copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            true
        }

        binding.scrollTerminal.setOnClickListener {
            showKeyboard()
        }

        // Keep keyboard toggle button state synced with IME visibility
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            isKeyboardOpen = imeVisible
            updateKeyboardToggleButton(imeVisible)
            ViewCompat.onApplyWindowInsets(view, insets)
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
        binding.etTerminalInput.onEnter = {
            sendUserInput("\r")
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
        val width = viewport.width - viewport.paddingLeft - viewport.paddingRight
        val height = viewport.height - viewport.paddingTop - viewport.paddingBottom

        val charWidth = binding.tvTerminalOutput.paint.measureText("M")
        val charHeight = binding.tvTerminalOutput.lineHeight.toFloat()

        if (width > 0 && height > 0 && charWidth > 0 && charHeight > 0) {
            val cols = (width / charWidth).toInt().coerceIn(20, 240)
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
            val firstChar = input[0]
            if (firstChar in 'a'..'z' || firstChar in 'A'..'Z') {
                val ctrlCode = (firstChar.uppercaseChar().code - 64).toChar()
                toSend = ctrlCode.toString() + input.substring(1)
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
        binding.scrollTerminal.post {
            binding.scrollTerminal.scrollTo(0, binding.tvTerminalOutput.bottom)
        }
    }

    private fun handleExit() {
        if (sshManager?.isConnected == true) {
            AlertDialog.Builder(this)
                .setTitle(R.string.terminal_disconnect)
                .setMessage(R.string.confirm_disconnect)
                .setPositiveButton(R.string.terminal_disconnect) { _, _ ->
                    sshManager?.disconnect()
                    finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            finish()
        }
    }

    override fun onBackPressed() {
        handleExit()
    }

    override fun onDestroy() {
        super.onDestroy()
        sshManager?.disconnect()
    }
}
