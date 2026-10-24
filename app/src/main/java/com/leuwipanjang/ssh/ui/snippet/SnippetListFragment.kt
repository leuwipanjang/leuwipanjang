package com.leuwipanjang.ssh.ui.snippet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.leuwipanjang.ssh.data.model.SshSnippet
import com.leuwipanjang.ssh.data.repository.SnippetRepository
import com.leuwipanjang.ssh.databinding.FragmentSnippetListBinding

class SnippetListFragment : Fragment() {

    private var _binding: FragmentSnippetListBinding? = null
    private val binding get() = _binding!!

    private lateinit var snippetRepository: SnippetRepository
    private lateinit var adapter: SnippetAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSnippetListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        snippetRepository = SnippetRepository(requireContext())

        setupRecyclerView()
        setupListeners()
        loadSnippets()
    }

    private fun setupRecyclerView() {
        adapter = SnippetAdapter(
            snippets = emptyList(),
            onItemClick = { snippet ->
                showEditSnippetDialog(snippet)
            },
            onDeleteClick = { snippet ->
                snippetRepository.delete(snippet.id)
                loadSnippets()
                Toast.makeText(requireContext(), "Snippet deleted", Toast.LENGTH_SHORT).show()
            }
        )

        binding.rvSnippets.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSnippets.adapter = adapter
    }

    private fun setupListeners() {
        binding.fabAddSnippet.setOnClickListener {
            showAddSnippetDialog()
        }
    }

    private fun loadSnippets() {
        val list = snippetRepository.getAll()
        adapter.updateData(list)
    }

    private fun showAddSnippetDialog() {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val etTitle = EditText(context).apply {
            hint = "Snippet Title (e.g. Docker Status)"
        }
        val etCommand = EditText(context).apply {
            hint = "Shell command (e.g. docker ps -a)"
        }
        layout.addView(etTitle)
        layout.addView(etCommand)

        AlertDialog.Builder(context)
            .setTitle("Add Terminal Snippet")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val title = etTitle.text.toString().trim()
                val command = etCommand.text.toString().trim()
                if (title.isNotEmpty() && command.isNotEmpty()) {
                    snippetRepository.save(
                        SshSnippet(
                            title = title,
                            command = if (command.endsWith("\n")) command else "$command\n"
                        )
                    )
                    loadSnippets()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditSnippetDialog(snippet: SshSnippet) {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val etTitle = EditText(context).apply {
            setText(snippet.title)
        }
        val etCommand = EditText(context).apply {
            setText(snippet.command.trimEnd())
        }
        layout.addView(etTitle)
        layout.addView(etCommand)

        AlertDialog.Builder(context)
            .setTitle("Edit Terminal Snippet")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val title = etTitle.text.toString().trim()
                val command = etCommand.text.toString().trim()
                if (title.isNotEmpty() && command.isNotEmpty()) {
                    snippet.title = title
                    snippet.command = if (command.endsWith("\n")) command else "$command\n"
                    snippetRepository.save(snippet)
                    loadSnippets()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
