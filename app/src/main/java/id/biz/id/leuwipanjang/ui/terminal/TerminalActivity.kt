package id.biz.id.leuwipanjang.ui.terminal

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import id.biz.id.leuwipanjang.R
import id.biz.id.leuwipanjang.data.model.AuthType
import id.biz.id.leuwipanjang.data.model.SshHost
import id.biz.id.leuwipanjang.data.model.SshKey
import id.biz.id.leuwipanjang.data.model.TerminalTheme
import id.biz.id.leuwipanjang.data.repository.HostRepository
import id.biz.id.leuwipanjang.data.repository.KeyRepository
import id.biz.id.leuwipanjang.data.repository.SnippetRepository
import id.biz.id.leuwipanjang.databinding.ActivityTerminalBinding
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
        ansiParser = AnsiParser(activeTheme)

        binding.tvTerminalTitle.text = targetHost.alias
        binding.tvTerminalSubtitle.text = "${targetHost.username}@${targetHost.hostname}:${targetHost.port}"

        // Apply theme styling to terminal view
        binding.root.setBackgroundColor(activeTheme.backgroundColor)
        binding.scrollTerminal.setBackgroundColor(activeTheme.backgroundColor)
        binding.tvTerminalOutput.setBackgroundColor(activeTheme.backgroundColor)
        binding.tvTerminalOutput.setTextColor(activeTheme.foregroundColor)
        binding.tvTerminalOutput.typeface = Typeface.MONOSPACE
        binding.tvTerminalOutput.textSize = targetHost.fontSize.toFloat()

        binding.btnBack.setOnClickListener { handleExit() }
        binding.btnDisconnect.setOnClickListener { handleExit() }
        binding.btnRunSnippet.setOnClickListener { showSnippetRunnerDialog() }
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
        sshManager?.send(chars)
    }

    private fun setupInputHandlers() {
        binding.btnSend.setOnClickListener {
            val text = binding.etTerminalInput.text?.toString().orEmpty()
            sendUserInput(text + "\r")
            binding.etTerminalInput.setText("")
        }

        binding.etTerminalInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                val text = binding.etTerminalInput.text?.toString().orEmpty()
                sendUserInput(text + "\r")
                binding.etTerminalInput.setText("")
                true
            } else false
        }

        binding.etTerminalInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    val text = binding.etTerminalInput.text?.toString().orEmpty()
                    sendUserInput(text + "\r")
                    binding.etTerminalInput.setText("")
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    private fun sendUserInput(input: String) {
        if (isCtrlActive && input.isNotEmpty()) {
            val firstChar = input[0]
            val ctrlCode = (firstChar.uppercaseChar().code - 64).toChar()
            sshManager?.send(ctrlCode.toString())
            isCtrlActive = false
            updateModifierButtonState(binding.keyCtrl, false)
        } else {
            sshManager?.send(input)
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
        scrollToBottom()
    }

    override fun onDataReceived(text: String) {
        val styled = ansiParser.parse(text)
        binding.tvTerminalOutput.append(styled)
        scrollToBottom()
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
            binding.scrollTerminal.fullScroll(View.FOCUS_DOWN)
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
