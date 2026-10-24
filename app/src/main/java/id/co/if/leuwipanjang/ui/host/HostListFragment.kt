package id.co.if.leuwipanjang.ui.host

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import id.co.if.leuwipanjang.data.model.SshHost
import id.co.if.leuwipanjang.data.repository.HostRepository
import id.co.if.leuwipanjang.databinding.FragmentHostListBinding
import id.co.if.leuwipanjang.ui.terminal.TerminalActivity

class HostListFragment : Fragment() {

    private var _binding: FragmentHostListBinding? = null
    private val binding get() = _binding!!

    private lateinit var hostRepository: HostRepository
    private lateinit var adapter: HostAdapter
    private var allHosts: List<SshHost> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHostListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hostRepository = HostRepository(requireContext())

        setupRecyclerView()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        loadHosts()
    }

    private fun setupRecyclerView() {
        adapter = HostAdapter(
            hosts = emptyList(),
            onItemClick = { host ->
                val intent = Intent(requireContext(), AddEditHostActivity::class.java).apply {
                    putExtra(AddEditHostActivity.EXTRA_HOST_ID, host.id)
                }
                startActivity(intent)
            },
            onConnectClick = { host ->
                val intent = Intent(requireContext(), TerminalActivity::class.java).apply {
                    putExtra(TerminalActivity.EXTRA_HOST_ID, host.id)
                }
                startActivity(intent)
            }
        )

        binding.rvHosts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHosts.adapter = adapter
    }

    private fun setupListeners() {
        binding.fabAddHost.setOnClickListener {
            val intent = Intent(requireContext(), AddEditHostActivity::class.java)
            startActivity(intent)
        }

        binding.etSearch.doAfterTextChanged { text ->
            filterHosts(text?.toString().orEmpty())
        }
    }

    private fun loadHosts() {
        allHosts = hostRepository.getAll()
        filterHosts(binding.etSearch.text?.toString().orEmpty())
    }

    private fun filterHosts(query: String) {
        val filtered = if (query.isBlank()) {
            allHosts
        } else {
            allHosts.filter {
                it.alias.contains(query, ignoreCase = true) ||
                        it.hostname.contains(query, ignoreCase = true) ||
                        it.username.contains(query, ignoreCase = true) ||
                        it.group.contains(query, ignoreCase = true)
            }
        }

        adapter.updateData(filtered)

        if (filtered.isEmpty()) {
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.rvHosts.visibility = View.GONE
        } else {
            binding.emptyStateLayout.visibility = View.GONE
            binding.rvHosts.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
