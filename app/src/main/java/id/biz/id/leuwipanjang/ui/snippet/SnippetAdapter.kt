package id.biz.id.leuwipanjang.ui.snippet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import id.biz.id.leuwipanjang.data.model.SshSnippet
import id.biz.id.leuwipanjang.databinding.ItemSnippetBinding

class SnippetAdapter(
    private var snippets: List<SshSnippet>,
    private val onItemClick: (SshSnippet) -> Unit,
    private val onDeleteClick: (SshSnippet) -> Unit
) : RecyclerView.Adapter<SnippetAdapter.SnippetViewHolder>() {

    fun updateData(newSnippets: List<SshSnippet>) {
        this.snippets = newSnippets
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SnippetViewHolder {
        val binding = ItemSnippetBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SnippetViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SnippetViewHolder, position: Int) {
        holder.bind(snippets[position])
    }

    override fun getItemCount(): Int = snippets.size

    inner class SnippetViewHolder(private val binding: ItemSnippetBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(snippet: SshSnippet) {
            binding.tvSnippetTitle.text = snippet.title
            binding.tvSnippetDesc.text = snippet.description ?: "Custom command snippet"
            binding.tvSnippetCommand.text = snippet.command.trimEnd()

            binding.root.setOnClickListener { onItemClick(snippet) }

            binding.btnCopySnippet.setOnClickListener {
                copyToClipboard(binding.root.context, snippet.command.trimEnd())
            }

            binding.btnDeleteSnippet.setOnClickListener {
                onDeleteClick(snippet)
            }
        }

        private fun copyToClipboard(context: Context, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Terminal Command", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Command copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }
}
