package com.leuwipanjang.ssh.data.security

import android.util.Base64
import com.leuwipanjang.ssh.data.model.KeyType
import com.leuwipanjang.ssh.data.model.SshKey
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.UUID

object KeyGeneratorHelper {

    fun generateKey(alias: String, keyType: KeyType, comment: String = "leuwipanjang@android"): SshKey {
        return when (keyType) {
            KeyType.ED25519 -> generateEd25519Key(alias, comment)
            KeyType.RSA_2048 -> generateRsaKey(alias, 2048, KeyType.RSA_2048, comment)
            KeyType.RSA_4096 -> generateRsaKey(alias, 4096, KeyType.RSA_4096, comment)
        }
    }

    private fun generateEd25519Key(alias: String, comment: String): SshKey {
        val random = SecureRandom()
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(random))

        val keyPair = generator.generateKeyPair()
        val privateParams = keyPair.private as Ed25519PrivateKeyParameters
        val publicParams = keyPair.public as Ed25519PublicKeyParameters

        val rawPublicKey = publicParams.encoded
        val wireBytes = SshKeyUtils.encodeEd25519PublicKey(rawPublicKey)
        val b64Pub = Base64.encodeToString(wireBytes, Base64.NO_WRAP)
        val openSshPublicKey = "ssh-ed25519 $b64Pub $comment"

        // Convert private key to standard PKCS#8 PEM
        val pkcs8Info = PrivateKeyInfoFactory.createPrivateKeyInfo(privateParams)
        val pemPrivateKey = SshKeyUtils.toPem("PRIVATE KEY", pkcs8Info.encoded)

        val fingerprint = SshKeyUtils.calculateFingerprint(wireBytes)

        return SshKey(
            id = UUID.randomUUID().toString(),
            alias = alias,
            keyType = KeyType.ED25519,
            publicKey = openSshPublicKey,
            privateKey = pemPrivateKey,
            fingerprint = fingerprint
        )
    }

    private fun generateRsaKey(alias: String, keySize: Int, keyType: KeyType, comment: String): SshKey {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(keySize, SecureRandom())
        val pair = keyGen.generateKeyPair()

        val rsaPub = pair.public as RSAPublicKey
        val rsaPriv = pair.private as RSAPrivateKey

        val wireBytes = SshKeyUtils.encodeRsaPublicKey(rsaPub)
        val b64Pub = Base64.encodeToString(wireBytes, Base64.NO_WRAP)
        val openSshPublicKey = "ssh-rsa $b64Pub $comment"

        val pemPrivateKey = SshKeyUtils.toPem("RSA PRIVATE KEY", rsaPriv.encoded)
        val fingerprint = SshKeyUtils.calculateFingerprint(wireBytes)

        return SshKey(
            id = UUID.randomUUID().toString(),
            alias = alias,
            keyType = keyType,
            publicKey = openSshPublicKey,
            privateKey = pemPrivateKey,
            fingerprint = fingerprint
        )
    }

    fun parseImportedKey(alias: String, privateKeyPem: String, userPublicKey: String? = null): SshKey {
        val cleanPem = privateKeyPem.trim()
        val isEd25519 = cleanPem.contains("ed25519", ignoreCase = true)
        val keyType = if (isEd25519) KeyType.ED25519 else KeyType.RSA_2048

        val pubKey = userPublicKey?.trim()?.ifEmpty { null } ?: "# Public key extracted from identity"
        val fingerprint = "SHA256:imported_key_${System.currentTimeMillis() % 100000}"

        return SshKey(
            id = UUID.randomUUID().toString(),
            alias = alias,
            keyType = keyType,
            publicKey = pubKey,
            privateKey = cleanPem,
            fingerprint = fingerprint
        )
    }
}
