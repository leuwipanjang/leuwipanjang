package id.co.if.leuwipanjang.data.security

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

object SshKeyUtils {

    /**
     * Encodes OpenSSH wire format for RSA Public Key:
     * string "ssh-rsa"
     * mpint  e (exponent)
     * mpint  n (modulus)
     */
    fun encodeRsaPublicKey(rsaPubKey: RSAPublicKey): ByteArray {
        val byteOs = ByteArrayOutputStream()
        val dos = DataOutputStream(byteOs)

        writeString(dos, "ssh-rsa")
        writeMpint(dos, rsaPubKey.publicExponent)
        writeMpint(dos, rsaPubKey.modulus)

        return byteOs.toByteArray()
    }

    /**
     * Encodes OpenSSH wire format for Ed25519 Public Key:
     * string "ssh-ed25519"
     * string (32 bytes raw public key)
     */
    fun encodeEd25519PublicKey(rawPublicKeyBytes: ByteArray): ByteArray {
        val byteOs = ByteArrayOutputStream()
        val dos = DataOutputStream(byteOs)

        writeString(dos, "ssh-ed25519")
        writeByteArray(dos, rawPublicKeyBytes)

        return byteOs.toByteArray()
    }

    /**
     * Calculates SHA256 OpenSSH fingerprint:
     * SHA256:Base64(SHA-256(wireBytes)) without trailing '='
     */
    fun calculateFingerprint(wireBytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(wireBytes)
        val b64 = Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        return "SHA256:$b64"
    }

    /**
     * Converts PKCS#8 or PKCS#1 private key bytes to standard PEM format string
     */
    fun toPem(type: String, encoded: ByteArray): String {
        val b64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
        val sb = StringBuilder()
        sb.append("-----BEGIN $type-----\n")
        var i = 0
        while (i < b64.length) {
            val end = (i + 64).coerceAtMost(b64.length)
            sb.append(b64.substring(i, end)).append("\n")
            i = end
        }
        sb.append("-----END $type-----\n")
        return sb.toString()
    }

    private fun writeString(dos: DataOutputStream, str: String) {
        val bytes = str.toByteArray(Charsets.US_ASCII)
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    private fun writeByteArray(dos: DataOutputStream, bytes: ByteArray) {
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    private fun writeMpint(dos: DataOutputStream, bi: BigInteger) {
        val bytes = bi.toByteArray()
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }
}
