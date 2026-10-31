package com.qcenaman.remoteai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.qcenaman.remoteai.net.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

data class Settings(
    val host: String = "100.109.168.97",
    val lmPort: Int = 1234,
    val approvalPort: Int = 1235,
    val tokenSaverPort: Int = 8766,
    val lmToken: String = "",
    val approvalToken: String = "",
    val tokenSaverToken: String = "",
    val startCommand: String = "\"D:\\Claude Apps\\ContextSaver\\app\\contextsaver.exe\" start",
    /** Max output tokens per generation (between tool calls); 0 = no limit. */
    val maxReplyTokens: Int = 8192,
    /** Stop a reply that only thinks for this long; 0 = off. */
    val stopThinkingMinutes: Int = 3,
)

interface SettingsStore {
    fun load(): Settings
    fun save(s: Settings)
}

/** Tokens live in Android's encrypted storage only. */
class EncryptedSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "remoteai_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun load(): Settings {
        val d = Settings()
        return Settings(
            host = prefs.getString("host", d.host) ?: d.host,
            lmPort = prefs.getInt("lmPort", d.lmPort),
            approvalPort = prefs.getInt("approvalPort", d.approvalPort),
            tokenSaverPort = prefs.getInt("tokenSaverPort", d.tokenSaverPort),
            lmToken = prefs.getString("lmToken", "") ?: "",
            approvalToken = prefs.getString("approvalToken", "") ?: "",
            tokenSaverToken = prefs.getString("tokenSaverToken", "") ?: "",
            startCommand = prefs.getString("startCommand", d.startCommand) ?: d.startCommand,
            maxReplyTokens = prefs.getInt("maxReplyTokens", d.maxReplyTokens),
            stopThinkingMinutes = prefs.getInt("stopThinkingMinutes", d.stopThinkingMinutes),
        )
    }

    override fun save(s: Settings) {
        prefs.edit()
            .putString("host", s.host.trim())
            .putInt("lmPort", s.lmPort)
            .putInt("approvalPort", s.approvalPort)
            .putInt("tokenSaverPort", s.tokenSaverPort)
            .putString("lmToken", s.lmToken.trim())
            .putString("approvalToken", s.approvalToken.trim())
            .putString("tokenSaverToken", s.tokenSaverToken.trim())
            .putString("startCommand", s.startCommand.trim())
            .putInt("maxReplyTokens", s.maxReplyTokens.coerceAtLeast(0))
            .putInt("stopThinkingMinutes", s.stopThinkingMinutes.coerceAtLeast(0))
            .apply()
    }
}

@Serializable
data class ChatItem(
    val role: String, // user | assistant | tool | note
    val text: String,
    val tool: String = "",
    val args: String = "",
    val reasoning: String = "",
    /** On a note: the user message a Retry button resends as a fresh attempt. */
    val retry: String = "",
    /** Tokens, speed and context shown under the message. */
    val stats: com.qcenaman.remoteai.net.ReplyStats? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val updated: Long,
    val responseId: String? = null,
    val model: String = "",
    val items: List<ChatItem> = emptyList(),
)

interface ConversationStore {
    fun all(): List<Conversation>
    fun put(c: Conversation)
    fun delete(id: String)
}

class FileConversationStore(private val file: File) : ConversationStore {
    private val ser = ListSerializer(Conversation.serializer())
    private var cache: MutableList<Conversation>? = null

    @Synchronized
    override fun all(): List<Conversation> {
        val c = cache ?: (runCatching { json.decodeFromString(ser, file.readText()) }.getOrNull() ?: emptyList())
            .toMutableList().also { cache = it }
        return c.sortedByDescending { it.updated }
    }

    @Synchronized
    override fun put(c: Conversation) {
        val list = all().filter { it.id != c.id }.toMutableList()
        list += c
        cache = list
        write(list)
    }

    @Synchronized
    override fun delete(id: String) {
        val list = all().filter { it.id != id }.toMutableList()
        cache = list
        write(list)
    }

    private fun write(list: List<Conversation>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ser, list))
        tmp.renameTo(file)
    }
}

class MemoryConversationStore : ConversationStore {
    private val map = linkedMapOf<String, Conversation>()
    override fun all() = map.values.sortedByDescending { it.updated }
    override fun put(c: Conversation) { map[c.id] = c }
    override fun delete(id: String) { map.remove(id) }
}
