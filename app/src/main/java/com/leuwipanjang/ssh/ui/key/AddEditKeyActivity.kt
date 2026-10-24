package com.leuwipanjang.ssh.ui.key

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.leuwipanjang.ssh.R
import com.leuwipanjang.ssh.data.model.KeyType
import com.leuwipanjang.ssh.data.model.SshKey
import com.leuwipanjang.ssh.data.repository.KeyRepository
import com.leuwipanjang.ssh.data.security.KeyGeneratorHelper
import com.leuwipanjang.ssh.databinding.ActivityAddEditKeyBinding

class AddEditKeyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddEditKeyBinding
    private lateinit var keyRepository: KeyRepository

    private var generatedKey: SshKey? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddEditKeyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keyRepository = KeyRepository(this)

        setupToolbar()
        setupKeyTypeSpinner()
        setupListeners()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupKeyTypeSpinner() {
        val types = KeyType.values().map { it.title }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        binding.spKeyType.adapter = adapter
    }

    private fun setupListeners() {
        binding.rgKeyMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbGenerate) {
                binding.sectionGenerate.visibility = View.VISIBLE
                binding.sectionImport.visibility = View.GONE
            } else {
                binding.sectionGenerate.visibility = View.GONE
                binding.sectionImport.visibility = View.VISIBLE
            }
        }

        binding.btnGenerateAction.setOnClickListener {
            generateKeyPair()
        }

        binding.btnCopyPreviewKey.setOnClickListener {
            generatedKey?.publicKey?.let { pubKey ->
                copyToClipboard(pubKey)
            }
        }

        binding.btnSaveKey.setOnClickListener {
            saveKey()
        }
    }

    private fun generateKeyPair() {
        val alias = binding.etKeyAlias.text?.toString()?.trim().orEmpty().ifEmpty { "New SSH Key" }
        val selectedTypeIndex = binding.spKeyType.selectedItemPosition
        val keyType = if (selectedTypeIndex in KeyType.values().indices) {
            KeyType.values()[selectedTypeIndex]
        } else KeyType.ED25519

        try {
            val key = KeyGeneratorHelper.generateKey(
                alias = alias,
                keyType = keyType,
                comment = "leuwipanjang@android"
            )
            generatedKey = key

            // Display in Preview
            binding.cardKeyPreview.visibility = View.VISIBLE
            binding.tvPublicKeyPreview.text = key.publicKey
            binding.tvFingerprintPreview.text = key.fingerprint

            Toast.makeText(this, "SSH Key pair generated successfully!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error generating key: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveKey() {
        val alias = binding.etKeyAlias.text?.toString()?.trim().orEmpty().ifEmpty { "SSH Key" }

        val isGenerateMode = binding.rbGenerate.isChecked

        if (isGenerateMode) {
            if (generatedKey == null) {
                // If user didn't manually press generate button, generate now
                generateKeyPair()
            }
            val keyToSave = generatedKey ?: return
            keyToSave.alias = alias
            keyRepository.save(keyToSave)
            Toast.makeText(this, "Key saved to Vault!", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            // Import Mode
            val privateKeyText = binding.etPrivateKeyInput.text?.toString()?.trim().orEmpty()
            if (privateKeyText.isEmpty()) {
                binding.etPrivateKeyInput.error = "Private key cannot be empty"
                return
            }

            try {
                val imported = KeyGeneratorHelper.parseImportedKey(
                    alias = alias,
                    privateKeyPem = privateKeyText
                )
                keyRepository.save(imported)
                Toast.makeText(this, "Imported key saved to Vault!", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to parse key: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SSH Public Key", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.public_key_copied), Toast.LENGTH_SHORT).show()
    }
}
