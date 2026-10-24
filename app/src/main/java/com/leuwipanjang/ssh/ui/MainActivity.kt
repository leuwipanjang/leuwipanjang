package com.leuwipanjang.ssh.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.leuwipanjang.ssh.R
import com.leuwipanjang.ssh.databinding.ActivityMainBinding
import com.leuwipanjang.ssh.ui.host.HostListFragment
import com.leuwipanjang.ssh.ui.key.KeyListFragment
import com.leuwipanjang.ssh.ui.settings.SettingsFragment
import com.leuwipanjang.ssh.ui.snippet.SnippetListFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val hostListFragment = HostListFragment()
    private val keyListFragment = KeyListFragment()
    private val snippetListFragment = SnippetListFragment()
    private val settingsFragment = SettingsFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            loadFragment(hostListFragment)
        }

        setupBottomNavigation()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_hosts -> {
                    loadFragment(hostListFragment)
                    binding.topToolbar.title = getString(R.string.hosts_title)
                    true
                }
                R.id.menu_keys -> {
                    loadFragment(keyListFragment)
                    binding.topToolbar.title = getString(R.string.keys_title)
                    true
                }
                R.id.menu_snippets -> {
                    loadFragment(snippetListFragment)
                    binding.topToolbar.title = getString(R.string.snippets_title)
                    true
                }
                R.id.menu_settings -> {
                    loadFragment(settingsFragment)
                    binding.topToolbar.title = getString(R.string.nav_settings)
                    true
                }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}
