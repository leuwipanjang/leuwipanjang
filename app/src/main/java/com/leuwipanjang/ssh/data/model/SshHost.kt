package com.leuwipanjang.ssh.data.model

import java.util.UUID

enum class AuthType {
    PASSWORD,
    KEY
}

data class SshHost(
    val id: String = UUID.randomUUID().toString(),
    var alias: String,
    var hostname: String,
    var port: Int = 22,
    var username: String,
    var authType: AuthType = AuthType.PASSWORD,
    var password: String? = null,
    var keyId: String? = null,
    var keyPassphrase: String? = null,
    var group: String = "Default",
    var themeId: String = "termius_dark",
    var fontSize: Int = 14,
    var keepAliveSeconds: Int = 30,
    var lastConnected: Long? = null,
    var notes: String? = null
)
