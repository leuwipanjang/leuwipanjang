package id.biz.id.leuwipanjang.ui.key

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import id.biz.id.leuwipanjang.R
import id.biz.id.leuwipanjang.data.model.SshKey
import id.biz.id.leuwipanjang.data.repository.KeyRepository
import id.biz.id.leuwipanjang.databinding.FragmentKeyListBinding

class KeyListFragment : Fragment() {

    private var _binding: FragmentKeyListBinding? = null
    private val binding get() = _binding!!

    private lateinit var keyRepository: KeyRepository
    private lateinit var adapter: KeyAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeyListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        keyRepository = KeyRepository(requireContext())

        setupRecyclerView()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        loadKeys()
    }

    private fun setupRecyclerView() {
        adapter = KeyAdapter(
            keys = emptyList(),
            onItemClick = { key ->
                val dialog = ViewPublicKeyDialog.newInstance(key)
                dialog.show(parentFragmentManager, "ViewPublicKeyDialog")
            },
            onDeleteClick = { key ->
                confirmDeleteKey(key)
            }
        )

        binding.rvKeys.layoutManager = LinearLayoutManager(requireContext())
        binding.rvKeys.adapter = adapter
    }

    private fun setupListeners() {
        binding.fabAddKey.setOnClickListener {
            val intent = Intent(requireContext(), AddEditKeyActivity::class.java)
            startActivity(intent)
        }
    }

    private fun loadKeys() {
        val keys = keyRepository.getAll()
        adapter.updateData(keys)

        if (keys.isEmpty()) {
            binding.emptyStateKeys.visibility = View.VISIBLE
            binding.rvKeys.visibility = View.GONE
        } else {
            binding.emptyStateKeys.visibility = View.GONE
            binding.rvKeys.visibility = View.VISIBLE
        }
    }

    private fun confirmDeleteKey(key: SshKey) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete SSH Key")
            .setMessage("Are you sure you want to delete '${key.alias}'? Any host using this key will no longer be able to authenticate.")
            .setPositiveButton("Delete") { _, _ ->
                keyRepository.delete(key.id)
                loadKeys()
                Toast.makeText(requireContext(), "Key deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
