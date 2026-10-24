package id.biz.id.leuwipanjang.data.model

import java.util.UUID

enum class KeyType(val title: String) {
    ED25519("Ed25519 (Recommended)"),
    RSA_2048("RSA 2048-bit"),
    RSA_4096("RSA 4096-bit")
}

data class SshKey(
    val id: String = UUID.randomUUID().toString(),
    var alias: String,
    var keyType: KeyType,
    var publicKey: String,
    var privateKey: String,
    var fingerprint: String,
    var createdAt: Long = System.currentTimeMillis()
)
