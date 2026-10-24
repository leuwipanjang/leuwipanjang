package id.co.if.leuwipanjang.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import id.co.if.leuwipanjang.data.model.AuthType
import id.co.if.leuwipanjang.data.model.SshHost

class HostRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("leuwipanjang_hosts", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "saved_hosts"

    init {
        // Initialize with friendly sample host if empty
        if (getAll().isEmpty()) {
            val sample = SshHost(
                alias = "LeuwiPanjang Demo Server",
                hostname = "demo.leuwipanjang.id",
                port = 22,
                username = "root",
                authType = AuthType.KEY,
                group = "Demo",
                notes = "Pre-configured sample SSH server"
            )
            save(sample)
        }
    }

    fun getAll(): List<SshHost> {
        val json = prefs.getString(key, null) ?: return emptyList()
        val type = object : TypeToken<List<SshHost>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getById(id: String): SshHost? {
        return getAll().find { it.id == id }
    }

    fun save(host: SshHost) {
        val list = getAll().toMutableList()
        val index = list.indexOfFirst { it.id == host.id }
        if (index >= 0) {
            list[index] = host
        } else {
            list.add(0, host)
        }
        persist(list)
    }

    fun delete(id: String) {
        val list = getAll().filterNot { it.id == id }
        persist(list)
    }

    fun updateLastConnected(id: String) {
        val host = getById(id) ?: return
        host.lastConnected = System.currentTimeMillis()
        save(host)
    }

    private fun persist(list: List<SshHost>) {
        val json = gson.toJson(list)
        prefs.edit().putString(key, json).apply()
    }
}
