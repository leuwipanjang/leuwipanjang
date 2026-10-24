package com.leuwipanjang.ssh.ui.host

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.leuwipanjang.ssh.data.model.AuthType
import com.leuwipanjang.ssh.data.model.SshHost
import com.leuwipanjang.ssh.databinding.ItemHostBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HostAdapter(
    private var hosts: List<SshHost>,
    private val onItemClick: (SshHost) -> Unit,
    private val onConnectClick: (SshHost) -> Unit
) : RecyclerView.Adapter<HostAdapter.HostViewHolder>() {

    fun updateData(newHosts: List<SshHost>) {
        this.hosts = newHosts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HostViewHolder {
        val binding = ItemHostBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HostViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HostViewHolder, position: Int) {
        holder.bind(hosts[position])
    }

    override fun getItemCount(): Int = hosts.size

    inner class HostViewHolder(private val binding: ItemHostBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(host: SshHost) {
            binding.tvHostAlias.text = host.alias
            binding.tvHostEndpoint.text = "${host.username}@${host.hostname}:${host.port}"
            binding.tvGroupBadge.text = host.group

            binding.tvAuthBadge.text = if (host.authType == AuthType.KEY) "SSH Key" else "Password"

            if (host.lastConnected != null && host.lastConnected!! > 0) {
                val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                binding.tvLastConnected.text = sdf.format(Date(host.lastConnected!!))
            } else {
                binding.tvLastConnected.text = "Never"
            }

            binding.root.setOnClickListener { onItemClick(host) }
            binding.btnQuickConnect.setOnClickListener { onConnectClick(host) }
        }
    }
}
