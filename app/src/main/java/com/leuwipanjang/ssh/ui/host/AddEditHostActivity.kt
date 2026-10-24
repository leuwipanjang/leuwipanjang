package com.leuwipanjang.ssh.ui.host

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.leuwipanjang.ssh.R
import com.leuwipanjang.ssh.data.model.AuthType
import com.leuwipanjang.ssh.data.model.SshHost
import com.leuwipanjang.ssh.data.model.SshKey
import com.leuwipanjang.ssh.data.model.TerminalTheme
import com.leuwipanjang.ssh.data.repository.HostRepository
import com.leuwipanjang.ssh.data.repository.KeyRepository
import com.leuwipanjang.ssh.databinding.ActivityAddEditHostBinding
import com.leuwipanjang.ssh.ui.key.AddEditKeyActivity

class AddEditHostActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST_ID = "extra_host_id"
    }

    private lateinit var binding: ActivityAddEditHostBinding
    private lateinit var hostRepository: HostRepository
    private lateinit var keyRepository: KeyRepository

    private var currentHost: SshHost? = null
    private var availableKeys: List<SshKey> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddEditHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hostRepository = HostRepository(this)
        keyRepository = KeyRepository(this)

        setupToolbar()
        setupThemeSpinner()
        setupListeners()

        val hostId = intent.getStringExtra(EXTRA_HOST_ID)
        if (hostId != null) {
            currentHost = hostRepository.getById(hostId)
            currentHost?.let { populateFields(it) }
        } else {
            binding.toolbar.title = getString(R.string.add_host)
            binding.btnDeleteHost.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        setupKeySpinner()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupThemeSpinner() {
        val themes = TerminalTheme.ALL_THEMES.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, themes)
        binding.spTheme.adapter = adapter
    }

    private fun setupKeySpinner() {
        availableKeys = keyRepository.getAll()
        val keyNames = availableKeys.map { "${it.alias} (${it.keyType.name})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, keyNames)
        binding.spSshKey.adapter = adapter

        // Select previously chosen key if any
        currentHost?.keyId?.let { keyId ->
            val index = availableKeys.indexOfFirst { it.id == keyId }
            if (index >= 0) {
                binding.spSshKey.setSelection(index)
            }
        }
    }

    private fun setupListeners() {
        binding.rgAuthType.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbAuthKey) {
                binding.sectionKeyAuth.visibility = View.VISIBLE
                binding.sectionPasswordAuth.visibility = View.GONE
            } else {
                binding.sectionKeyAuth.visibility = View.GONE
                binding.sectionPasswordAuth.visibility = View.VISIBLE
            }
        }

        binding.btnCreateNewKey.setOnClickListener {
            val intent = Intent(this, AddEditKeyActivity::class.java)
            startActivity(intent)
        }

        binding.btnSaveHost.setOnClickListener { saveHost() }

        binding.btnDeleteHost.setOnClickListener { confirmDelete() }
    }

    private fun populateFields(host: SshHost) {
        binding.toolbar.title = getString(R.string.edit_host)
        binding.etAlias.setText(host.alias)
        binding.etHostname.setText(host.hostname)
        binding.etPort.setText(host.port.toString())
        binding.etUsername.setText(host.username)
        binding.etGroup.setText(host.group)

        if (host.authType == AuthType.KEY) {
            binding.rbAuthKey.isChecked = true
            binding.sectionKeyAuth.visibility = View.VISIBLE
            binding.sectionPasswordAuth.visibility = View.GONE
            binding.etKeyPassphrase.setText(host.keyPassphrase.orEmpty())
        } else {
            binding.rbAuthPassword.isChecked = true
            binding.sectionKeyAuth.visibility = View.GONE
            binding.sectionPasswordAuth.visibility = View.VISIBLE
            binding.etPassword.setText(host.password.orEmpty())
        }

        val themeIndex = TerminalTheme.ALL_THEMES.indexOfFirst { it.id == host.themeId }
        if (themeIndex >= 0) {
            binding.spTheme.setSelection(themeIndex)
        }

        binding.btnDeleteHost.visibility = View.VISIBLE
    }

    private fun saveHost() {
        val alias = binding.etAlias.text?.toString()?.trim().orEmpty()
        val hostname = binding.etHostname.text?.toString()?.trim().orEmpty()
        val portStr = binding.etPort.text?.toString()?.trim().orEmpty()
        val username = binding.etUsername.text?.toString()?.trim().orEmpty()
        val group = binding.etGroup.text?.toString()?.trim().orEmpty().ifEmpty { "Default" }

        if (hostname.isEmpty()) {
            binding.etHostname.error = "Hostname/IP is required"
            return
        }

        val port = portStr.toIntOrNull() ?: 22
        val finalAlias = alias.ifEmpty { "$username@$hostname" }

        val isKeyAuth = binding.rbAuthKey.isChecked
        val authType = if (isKeyAuth) AuthType.KEY else AuthType.PASSWORD

        var selectedKeyId: String? = null
        var keyPassphrase: String? = null
        var password: String? = null

        if (isKeyAuth) {
            val selectedKeyPosition = binding.spSshKey.selectedItemPosition
            if (selectedKeyPosition in availableKeys.indices) {
                selectedKeyId = availableKeys[selectedKeyPosition].id
            } else {
                Toast.makeText(this, "Please select or create an SSH Key first", Toast.LENGTH_SHORT).show()
                return
            }
            keyPassphrase = binding.etKeyPassphrase.text?.toString()?.trim()
        } else {
            password = binding.etPassword.text?.toString()
        }

        val selectedThemePosition = binding.spTheme.selectedItemPosition
        val themeId = if (selectedThemePosition in TerminalTheme.ALL_THEMES.indices) {
            TerminalTheme.ALL_THEMES[selectedThemePosition].id
        } else "termius_dark"

        val host = currentHost?.copy(
            alias = finalAlias,
            hostname = hostname,
            port = port,
            username = username.ifEmpty { "root" },
            authType = authType,
            password = password,
            keyId = selectedKeyId,
            keyPassphrase = keyPassphrase,
            group = group,
            themeId = themeId
        ) ?: SshHost(
            alias = finalAlias,
            hostname = hostname,
            port = port,
            username = username.ifEmpty { "root" },
            authType = authType,
            password = password,
            keyId = selectedKeyId,
            keyPassphrase = keyPassphrase,
            group = group,
            themeId = themeId
        )

        hostRepository.save(host)
        Toast.makeText(this, "Host saved successfully!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_host)
            .setMessage(R.string.confirm_delete_host)
            .setPositiveButton(R.string.delete) { _, _ ->
                currentHost?.id?.let { hostRepository.delete(it) }
                Toast.makeText(this, "Host deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
