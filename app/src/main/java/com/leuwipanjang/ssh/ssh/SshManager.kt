package com.leuwipanjang.ssh.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.leuwipanjang.ssh.data.model.AuthType
import com.leuwipanjang.ssh.data.model.SshHost
import com.leuwipanjang.ssh.data.model.SshKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class SshManager(
    private val host: SshHost,
    private val sshKey: SshKey? = null,
    private val listener: SshConnectionListener
) {
    private var jsch: JSch? = null
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var readerJob: Job? = null

    var isConnected: Boolean = false
        private set

    fun connect() {
        scope.launch {
            try {
                withContext(Dispatchers.Main) { listener.onConnecting() }

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
                ch.setPtyType("xterm-256color")
                ch.setPtySize(80, 24, 640, 480)

                inputStream = ch.inputStream
                outputStream = ch.outputStream

                ch.connect(10000)

                isConnected = true
                withContext(Dispatchers.Main) { listener.onConnected() }

                // Start async reader loop
                startReadingOutput()

            } catch (e: Exception) {
                isConnected = false
                val errorMsg = e.localizedMessage ?: e.message ?: "Failed to establish SSH connection"
                withContext(Dispatchers.Main) {
                    listener.onError(errorMsg)
                    listener.onDisconnected()
                }
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
                        withContext(Dispatchers.Main) {
                            listener.onDataReceived(text)
                        }
                    } else if (read == -1) {
                        break
                    }
                }
            } catch (e: Exception) {
                // Channel closed or network interrupted
            } finally {
                isConnected = false
                withContext(Dispatchers.Main) {
                    listener.onDisconnected()
                }
            }
        }
    }

    fun send(text: String) {
        scope.launch {
            try {
                outputStream?.let { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
            } catch (e: Exception) {
                // Handle write error
            }
        }
    }

    fun sendBytes(bytes: ByteArray) {
        scope.launch {
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

    fun updatePtySize(cols: Int, rows: Int) {
        try {
            channel?.setPtySize(cols, rows, cols * 8, rows * 16)
        } catch (e: Exception) {
            // Ignore pty resize error
        }
    }

    fun disconnect() {
        isConnected = false
        readerJob?.cancel()
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
}
