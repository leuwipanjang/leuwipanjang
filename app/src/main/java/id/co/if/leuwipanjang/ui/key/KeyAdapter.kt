package id.co.if.leuwipanjang.ui.key

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import id.co.if.leuwipanjang.data.model.SshKey
import id.co.if.leuwipanjang.databinding.ItemKeyBinding

class KeyAdapter(
    private var keys: List<SshKey>,
    private val onItemClick: (SshKey) -> Unit,
    private val onDeleteClick: (SshKey) -> Unit
) : RecyclerView.Adapter<KeyAdapter.KeyViewHolder>() {

    fun updateData(newKeys: List<SshKey>) {
        this.keys = newKeys
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KeyViewHolder {
        val binding = ItemKeyBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return KeyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: KeyViewHolder, position: Int) {
        holder.bind(keys[position])
    }

    override fun getItemCount(): Int = keys.size

    inner class KeyViewHolder(private val binding: ItemKeyBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(key: SshKey) {
            binding.tvKeyAlias.text = key.alias
            binding.tvKeyTypeBadge.text = key.keyType.name
            binding.tvFingerprint.text = key.fingerprint

            binding.root.setOnClickListener { onItemClick(key) }

            binding.btnCopyPublicKey.setOnClickListener {
                copyToClipboard(binding.root.context, key.publicKey)
            }

            binding.btnDeleteKey.setOnClickListener {
                onDeleteClick(key)
            }
        }

        private fun copyToClipboard(context: Context, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SSH Public Key", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Public key copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }
}
