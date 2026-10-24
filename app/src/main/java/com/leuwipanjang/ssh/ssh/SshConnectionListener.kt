package com.leuwipanjang.ssh.ssh

interface SshConnectionListener {
    fun onConnecting()
    fun onConnected()
    fun onDataReceived(text: String)
    fun onError(errorMessage: String)
    fun onDisconnected()
}
