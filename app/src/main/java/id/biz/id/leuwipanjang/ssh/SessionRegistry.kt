package id.biz.id.leuwipanjang.ssh

import android.content.Context
import androidx.core.content.ContextCompat
import id.biz.id.leuwipanjang.data.model.SshHost
import id.biz.id.leuwipanjang.data.model.SshKey

/**
 * Process-wide list of live SSH sessions, one per host. Sessions outlive the terminal
 * screen; [SshSessionService] keeps the process in the foreground while any exist.
 * All functions run on the main thread.
 */
object SessionRegistry {

    private val sessions = LinkedHashMap<String, SshManager>()
    private val observers = mutableSetOf<() -> Unit>()

    /** Live sessions, oldest first. */
    val all: List<SshManager> get() = sessions.values.toList()

    /** The still-open session for [hostId], if any. */
    fun get(hostId: String): SshManager? = sessions[hostId]?.takeUnless { it.isClosed }

    /** Starts a new session for [host] and the foreground service that keeps it alive. */
    fun open(context: Context, host: SshHost, key: SshKey?): SshManager {
        sessions.remove(host.id)?.disconnect()
        val session = SshManager(host, key)
        sessions[host.id] = session
        // Called from the visible terminal screen, so starting a foreground service is allowed.
        ContextCompat.startForegroundService(
            context.applicationContext,
            SshSessionService.intent(context)
        )
        notifyObservers()
        session.connect()
        return session
    }

    /** Ends [session] at the user's request. */
    fun close(session: SshManager) {
        session.disconnect()
        onSessionClosed(session)
    }

    fun closeAll() {
        all.forEach { close(it) }
    }

    internal fun onSessionClosed(session: SshManager) {
        if (sessions[session.host.id] === session) {
            sessions.remove(session.host.id)
            notifyObservers()
        }
    }

    fun observe(observer: () -> Unit) {
        observers += observer
    }

    fun removeObserver(observer: () -> Unit) {
        observers -= observer
    }

    private fun notifyObservers() {
        observers.toList().forEach { it() }
    }
}
