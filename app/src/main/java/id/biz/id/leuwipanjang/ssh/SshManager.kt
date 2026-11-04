package id.biz.id.leuwipanjang.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import id.biz.id.leuwipanjang.data.model.AuthType
import id.biz.id.leuwipanjang.data.model.SshHost
import id.biz.id.leuwipanjang.data.model.SshKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * One SSH shell session. It is owned by [SessionRegistry] (kept alive by
 * [SshSessionService]), not by the terminal screen, so it survives the app going to the
 * background, the screen turning off and the activity being recreated. A terminal screen
 * [attach]es to receive output and replays [transcript] to restore what it missed.
 */
class SshManager(
    val host: SshHost,
    private val sshKey: SshKey? = null
) {
    private val lock = Any()
    private val transcript = StringBuilder()

    /** Current terminal screen, or null while the session runs in the background. Main thread only. */
    private var listener: SshConnectionListener? = null
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    private var jsch: JSch? = null
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var readerJob: Job? = null
    private var writerJob: Job? = null
    private val sendChannel = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    @Volatile
    var isConnected: Boolean = false
        private set

    /** True once the session has ended (failed, dropped or disconnected); it cannot be reused. */
    val isClosed: Boolean get() = closed.get()

    /**
     * Makes [l] the receiver of further callbacks and returns the output produced so far,
     * which the caller should render before anything else. Main thread only.
     */
    fun attach(l: SshConnectionListener): String = synchronized(lock) {
        listener = l
        transcript.toString()
    }

    /** Stops callbacks to [l] if it is still the attached screen. Main thread only. */
    fun detach(l: SshConnectionListener) {
        if (listener === l) listener = null
    }

    private suspend fun onMain(block: (SshConnectionListener) -> Unit) {
        withContext(Dispatchers.Main) { listener?.let(block) }
    }

    /** Runs once when the session ends, whichever path ends it. */
    private suspend fun notifyClosed() {
        if (!closed.compareAndSet(false, true)) return
        isConnected = false
        withContext(Dispatchers.Main) {
            listener?.onDisconnected()
            SessionRegistry.onSessionClosed(this@SshManager)
        }
    }

    fun connect() {
        scope.launch {
            try {
                onMain { it.onConnecting() }

                jsch = JSch()

                // Configure Authentication
                if (host.authType == AuthType.KEY) {
                    if (sshKey == null || sshKey.privateKey.isBlank()) {
                        throw IllegalArgumentException("SSH Public/Private Key is missing or invalid.")
                    }
                    val privKeyBytes = sshKey.privateKey.toByteArray(Charsets.UTF_8)
                    val pubKeyBytes = if (sshKey.publicKey.isNotBlank()) {
                        sshKey.publicKey.toByteArray(Charsets.UTF_8)
                    } else null

                    val passphraseBytes = host.keyPassphrase?.takeIf { it.isNotBlank() }
                        ?.toByteArray(Charsets.UTF_8)

                    jsch?.addIdentity(
                        sshKey.alias,
                        privKeyBytes,
                        pubKeyBytes,
                        passphraseBytes
                    )
                }

                // Create session
                val sess = jsch!!.getSession(host.username, host.hostname, host.port)
                session = sess

                if (host.authType == AuthType.PASSWORD && !host.password.isNullOrBlank()) {
                    sess.setPassword(host.password)
                }

                // Modern SSH session properties
                val config = java.util.Properties()
                config["StrictHostKeyChecking"] = "no"
                config["PreferredAuthentications"] = if (host.authType == AuthType.KEY) {
                    "publickey,keyboard-interactive,password"
                } else {
                    "password,keyboard-interactive,publickey"
                }
                sess.setConfig(config)
                sess.setServerAliveInterval(host.keepAliveSeconds * 1000)
                sess.timeout = 15000

                // Connect session
                sess.connect(15000)

                // Open interactive shell channel
                val ch = sess.openChannel("shell") as ChannelShell
                channel = ch
                ch.setPty(true)
                ch.setPtyType("xterm-256color")
                ch.setPtySize(80, 24, 640, 480)

                // Configure standard terminal modes (VERASE = 0x7F / 127 DEL)
                val terminalModes = byteArrayOf(
                    3,             // TTY opcode 3 for VERASE (erase character)
                    0, 0, 0, 0x7F, // 32-bit integer argument: 127 DEL
                    0              // TTY_OP_END
                )
                ch.setTerminalMode(terminalModes)

                inputStream = ch.inputStream
                outputStream = ch.outputStream

                ch.connect(10000)

                if (isClosed) {
                    // disconnect() was called while connecting.
                    ch.disconnect()
                    sess.disconnect()
                    return@launch
                }
                isConnected = true
                onMain { it.onConnected() }

                // Start async reader and writer loops
                startReadingOutput()
                startWriterLoop()

            } catch (e: Exception) {
                isConnected = false
                val errorMsg = e.localizedMessage ?: e.message ?: "Failed to establish SSH connection"
                onMain { it.onError(errorMsg) }
                notifyClosed()
                disconnect()
            }
        }
    }

    private fun startReadingOutput() {
        readerJob = scope.launch {
            val buffer = ByteArray(4096)
            val stream = inputStream ?: return@launch
            try {
                while (isActive && isConnected && channel?.isConnected == true) {
                    val read = stream.read(buffer)
                    if (read > 0) {
                        val text = String(buffer, 0, read, Charsets.UTF_8)
                        // Record and pick the receiver atomically with attach(), so a screen
                        // attaching now gets each chunk exactly once (replay or callback).
                        val target = synchronized(lock) {
                            transcript.append(text)
                            if (transcript.length > MAX_TRANSCRIPT) {
                                transcript.delete(0, transcript.length - MAX_TRANSCRIPT)
                            }
                            listener
                        }
                        withContext(Dispatchers.Main) {
                            // Skip if that screen detached meanwhile; the next one replays it.
                            if (target != null && target === listener) {
                                try {
                                    target.onDataReceived(text)
                                } catch (t: Throwable) {
                                    android.util.Log.e("LeuwiPanjang", "Error in onDataReceived", t)
                                }
                            }
                        }
                    } else if (read == -1) {
                        break
                    }
                }
            } catch (e: Exception) {
                // Channel closed or network interrupted
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { notifyClosed() }
            }
        }
    }

    private fun startWriterLoop() {
        writerJob = scope.launch {
            for (bytes in sendChannel) {
                try {
                    outputStream?.let { os ->
                        os.write(bytes)
                        os.flush()
                    }
                } catch (e: Exception) {
                    // Handle write error
                }
            }
        }
    }

    fun send(text: String) {
        sendBytes(text.toByteArray(Charsets.UTF_8))
    }

    fun sendBytes(bytes: ByteArray) {
        sendChannel.trySend(bytes)
    }

    fun updatePtySize(cols: Int, rows: Int) {
        scope.launch {
            try {
                if (isConnected && channel?.isConnected == true) {
                    channel?.setPtySize(cols, rows, cols * 8, rows * 16)
                }
            } catch (e: Exception) {
                // Ignore pty resize error
            }
        }
    }

    fun disconnect() {
        isConnected = false
        if (readerJob == null) {
            // Never reached the reader loop (still connecting): close the session here.
            scope.launch { notifyClosed() }
        }
        readerJob?.cancel()
        writerJob?.cancel()
        scope.launch {
            try {
                channel?.disconnect()
                session?.disconnect()
            } catch (e: Exception) {
                // Ignore cleanup errors
            } finally {
                channel = null
                session = null
                inputStream = null
                outputStream = null
            }
        }
    }

    companion object {
        /** Characters of output kept for replay when a terminal screen re-attaches. */
        private const val MAX_TRANSCRIPT = 256 * 1024
    }
}
