package id.biz.id.leuwipanjang.ssh

interface SshConnectionListener {
    fun onConnecting()
    fun onConnected()
    fun onDataReceived(text: String)
    fun onError(errorMessage: String)
    fun onDisconnected()

    /**
     * The program is about to be asked to repaint (two quick PTY size changes, so two
     * repaints follow). A screen can hold back drawing until they are through.
     */
    fun onRedrawStarted() {}
}
