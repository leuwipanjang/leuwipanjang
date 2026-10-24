package id.biz.id.leuwipanjang.ui.key

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import id.biz.id.leuwipanjang.R
import id.biz.id.leuwipanjang.data.model.SshKey

class ViewPublicKeyDialog : DialogFragment() {

    companion object {
        fun newInstance(key: SshKey): ViewPublicKeyDialog {
            val dialog = ViewPublicKeyDialog()
            dialog.sshKey = key
            return dialog
        }
    }

    var sshKey: SshKey? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val key = sshKey ?: return super.onCreateDialog(savedInstanceState)
        val context = requireContext()

        val view = layoutInflater.inflate(R.layout.dialog_view_public_key, null)
        val tvAlias = view.findViewById<TextView>(R.id.tvDialogKeyAlias)
        val tvFingerprint = view.findViewById<TextView>(R.id.tvDialogFingerprint)
        val tvPublicKey = view.findViewById<TextView>(R.id.tvDialogPublicKey)
        val btnCopy = view.findViewById<MaterialButton>(R.id.btnDialogCopyKey)
        val btnCopyOneLiner = view.findViewById<MaterialButton>(R.id.btnDialogCopyOneLiner)

        tvAlias.text = "${key.alias} (${key.keyType.name})"
        tvFingerprint.text = key.fingerprint
        tvPublicKey.text = key.publicKey

        btnCopy.setOnClickListener {
            copyToClipboard(context, "SSH Public Key", key.publicKey)
            Toast.makeText(context, "Public key copied to clipboard!", Toast.LENGTH_SHORT).show()
        }

        btnCopyOneLiner.setOnClickListener {
            val oneLiner = "echo '${key.publicKey.trim()}' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
            copyToClipboard(context, "SSH Authorized Keys Command", oneLiner)
            Toast.makeText(context, "Copied remote authorized_keys command!", Toast.LENGTH_SHORT).show()
        }

        return AlertDialog.Builder(context)
            .setView(view)
            .setPositiveButton("Close", null)
            .create()
    }

    private fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }
}
