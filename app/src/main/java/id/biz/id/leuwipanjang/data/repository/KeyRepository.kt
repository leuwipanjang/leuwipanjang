package id.biz.id.leuwipanjang.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import id.biz.id.leuwipanjang.data.model.KeyType
import id.biz.id.leuwipanjang.data.model.SshKey
import id.biz.id.leuwipanjang.data.security.KeyGeneratorHelper

class KeyRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("leuwipanjang_keys", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val prefKey = "ssh_key_vault"

    init {
        // Automatically create a default Ed25519 identity key on first launch
        if (getAll().isEmpty()) {
            val defaultKey = KeyGeneratorHelper.generateKey(
                alias = "Default Ed25519 Key",
                keyType = KeyType.ED25519,
                comment = "leuwipanjang@android"
            )
            save(defaultKey)
        }
    }

    fun getAll(): List<SshKey> {
        val json = prefs.getString(prefKey, null) ?: return emptyList()
        val type = object : TypeToken<List<SshKey>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getById(id: String): SshKey? {
        return getAll().find { it.id == id }
    }

    fun save(key: SshKey) {
        val list = getAll().toMutableList()
        val index = list.indexOfFirst { it.id == key.id }
        if (index >= 0) {
            list[index] = key
        } else {
            list.add(0, key)
        }
        persist(list)
    }

    fun delete(id: String) {
        val list = getAll().filterNot { it.id == id }
        persist(list)
    }

    private fun persist(list: List<SshKey>) {
        val json = gson.toJson(list)
        prefs.edit().putString(prefKey, json).apply()
    }
}
