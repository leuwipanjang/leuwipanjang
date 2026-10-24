package id.co.if.leuwipanjang.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import id.co.if.leuwipanjang.data.model.SshSnippet

class SnippetRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("leuwipanjang_snippets", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val prefKey = "ssh_snippets"

    init {
        if (getAll().isEmpty()) {
            val defaultSnippets = listOf(
                SshSnippet(
                    title = "System Resource Monitor",
                    command = "htop || top\n",
                    description = "Monitor CPU, RAM, and process activity"
                ),
                SshSnippet(
                    title = "Disk Space Free",
                    command = "df -h\n",
                    description = "Check file system disk space"
                ),
                SshSnippet(
                    title = "Docker Status",
                    command = "docker ps -a\n",
                    description = "List all Docker containers"
                ),
                SshSnippet(
                    title = "Memory Usage",
                    command = "free -h\n",
                    description = "Display amount of free and used memory"
                ),
                SshSnippet(
                    title = "Uptime & Load Average",
                    command = "uptime\n",
                    description = "Show system uptime and current load"
                ),
                SshSnippet(
                    title = "Listening Network Ports",
                    command = "ss -tulpn || netstat -tulpn\n",
                    description = "Inspect open TCP and UDP listening sockets"
                ),
                SshSnippet(
                    title = "Git Pull Latest",
                    command = "git pull\n",
                    description = "Pull latest commits from remote"
                ),
                SshSnippet(
                    title = "Nginx Service Status",
                    command = "systemctl status nginx\n",
                    description = "Check web server status"
                )
            )
            persist(defaultSnippets)
        }
    }

    fun getAll(): List<SshSnippet> {
        val json = prefs.getString(prefKey, null) ?: return emptyList()
        val type = object : TypeToken<List<SshSnippet>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(snippet: SshSnippet) {
        val list = getAll().toMutableList()
        val index = list.indexOfFirst { it.id == snippet.id }
        if (index >= 0) {
            list[index] = snippet
        } else {
            list.add(snippet)
        }
        persist(list)
    }

    fun delete(id: String) {
        val list = getAll().filterNot { it.id == id }
        persist(list)
    }

    private fun persist(list: List<SshSnippet>) {
        val json = gson.toJson(list)
        prefs.edit().putString(prefKey, json).apply()
    }
}
