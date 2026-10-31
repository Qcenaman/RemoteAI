package com.qcenaman.remoteai

import com.qcenaman.remoteai.data.ChatItem
import com.qcenaman.remoteai.data.Conversation
import com.qcenaman.remoteai.data.ConversationStore
import com.qcenaman.remoteai.data.Settings
import com.qcenaman.remoteai.data.SettingsStore
import com.qcenaman.remoteai.net.ApiException
import com.qcenaman.remoteai.net.Approval
import com.qcenaman.remoteai.net.ApprovalClient
import com.qcenaman.remoteai.net.LmStudioClient
import com.qcenaman.remoteai.net.ModelInfo
import com.qcenaman.remoteai.net.TokenSaverClient
import com.qcenaman.remoteai.net.baseUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class Link { UNKNOWN, OK, NO_TOKEN, BAD_TOKEN, OFFLINE, ERROR }

data class UiState(
    val settings: Settings = Settings(),
    val conversation: Conversation = newConv(),
    val conversations: List<Conversation> = emptyList(),
    val models: List<ModelInfo> = emptyList(),
    val model: String = "",
    val busy: Boolean = false,
    val pending: List<Approval> = emptyList(),
    val deciding: Set<String> = emptySet(),
    val lm: Link = Link.UNKNOWN,
    val approvals: Link = Link.UNKNOWN,
    val lmMessage: String = "",
    val starting: Boolean = false,
    val startOutput: String = "",
)

fun newConv() = Conversation(UUID.randomUUID().toString(), "New chat", System.currentTimeMillis())

/** All app logic, free of Android so it can be tested on the JVM. */
class ChatController(
    private val settingsStore: SettingsStore,
    private val store: ConversationStore,
    lmFactory: ((() -> String), (() -> String)) -> LmStudioClient = { b, t -> LmStudioClient(b, t) },
    approvalFactory: ((() -> String), (() -> String)) -> ApprovalClient = { b, t -> ApprovalClient(b, t) },
    tokenSaverFactory: ((() -> String), (() -> String)) -> TokenSaverClient = { b, t -> TokenSaverClient(b, t) },
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private val s get() = _state.value.settings
    private val chatLock = Mutex()

    private val lm = lmFactory({ baseUrl(s.host, s.lmPort) }, { s.lmToken })
    private val approvalsApi = approvalFactory({ baseUrl(s.host, s.approvalPort) }, { s.approvalToken })
    private val tokenSaver = tokenSaverFactory({ baseUrl(s.host, s.tokenSaverPort) }, { s.tokenSaverToken })

    init {
        val all = store.all()
        _state.value = UiState(
            settings = settingsStore.load(),
            conversation = all.firstOrNull() ?: newConv(),
            conversations = all,
        )
        _state.update { it.copy(model = it.conversation.model) }
    }

    fun saveSettings(new: Settings) {
        settingsStore.save(new)
        _state.update { it.copy(settings = new, lm = Link.UNKNOWN, approvals = Link.UNKNOWN, lmMessage = "") }
    }

    fun selectModel(id: String) = _state.update { it.copy(model = id) }

    fun newConversation() = _state.update { it.copy(conversation = newConv()) }

    fun openConversation(id: String) {
        val c = store.all().firstOrNull { it.id == id } ?: return
        _state.update { it.copy(conversation = c, model = c.model.ifBlank { it.model }) }
    }

    fun deleteConversation(id: String) {
        store.delete(id)
        _state.update {
            it.copy(
                conversations = store.all(),
                conversation = if (it.conversation.id == id) newConv() else it.conversation,
            )
        }
    }

    private fun linkFor(e: ApiException): Link = when (e) {
        is ApiException.Unauthorized -> Link.BAD_TOKEN
        is ApiException.Network -> Link.OFFLINE
        else -> Link.ERROR
    }

    suspend fun refreshModels(): Boolean {
        if (s.lmToken.isBlank()) {
            _state.update { it.copy(lm = Link.NO_TOKEN) }
            return false
        }
        return try {
            val ms = lm.models()
            _state.update { st ->
                val loaded = ms.filter { it.loaded }
                val keep = loaded.any { it.id == st.model }
                st.copy(
                    models = ms,
                    model = if (keep) st.model else loaded.firstOrNull()?.id ?: "",
                    lm = Link.OK,
                    lmMessage = if (loaded.isEmpty()) "No model loaded on the PC." else "",
                )
            }
            true
        } catch (e: ApiException) {
            _state.update { it.copy(lm = linkFor(e), lmMessage = e.message ?: "") }
            false
        }
    }

    suspend fun pollApprovals() {
        if (s.approvalToken.isBlank()) {
            _state.update { it.copy(approvals = Link.NO_TOKEN) }
            return
        }
        try {
            val list = approvalsApi.pending()
            _state.update { it.copy(pending = list, approvals = Link.OK) }
        } catch (e: ApiException) {
            _state.update { it.copy(approvals = linkFor(e)) }
        }
    }

    private fun saveConv(update: (Conversation) -> Conversation) {
        _state.update { st ->
            val c = update(st.conversation).copy(updated = System.currentTimeMillis())
            if (c.items.isNotEmpty()) store.put(c)
            st.copy(conversation = c, conversations = store.all())
        }
    }

    private fun note(text: String) = saveConv { it.copy(items = it.items + ChatItem("note", text)) }

    suspend fun send(text: String) {
        val input = text.trim()
        if (input.isEmpty()) return
        chatLock.withLock {
            saveConv { c ->
                c.copy(
                    title = if (c.items.isEmpty()) input.take(48) else c.title,
                    items = c.items + ChatItem("user", input),
                )
            }
            _state.update { it.copy(busy = true) }
            try {
                if (_state.value.model.isBlank() || _state.value.lm != Link.OK) refreshModels()
                val st = _state.value
                when {
                    st.lm == Link.NO_TOKEN -> note("Add the LM Studio token in Settings.")
                    st.lm == Link.BAD_TOKEN -> note("LM Studio rejected the token. Check it in Settings.")
                    st.lm != Link.OK -> note(st.lmMessage.ifBlank { "Can't reach LM Studio." })
                    st.model.isBlank() -> note("No model is loaded on the PC. Tap Start Local AI.")
                    else -> {
                        val reply = lm.chat(st.model, input, st.conversation.responseId)
                        if (reply.approvals.isNotEmpty()) {
                            _state.update { u -> u.copy(pending = (u.pending + reply.approvals).distinctBy { a -> a.id }) }
                        }
                        saveConv { c ->
                            val tools = reply.toolCalls.map { ChatItem("tool", it.output, tool = it.tool, args = it.arguments) }
                            val answer = if (reply.text.isNotBlank() || reply.reasoning.isNotBlank())
                                listOf(ChatItem("assistant", reply.text, reasoning = reply.reasoning)) else emptyList()
                            c.copy(
                                items = c.items + tools + answer,
                                responseId = reply.responseId ?: c.responseId,
                                model = st.model,
                            )
                        }
                    }
                }
            } catch (e: ApiException) {
                _state.update { it.copy(lm = linkFor(e), lmMessage = e.message ?: "") }
                note(e.message ?: "Request failed")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
        pollApprovals()
    }

    suspend fun approve(id: String) = decide(id, approve = true)
    suspend fun deny(id: String) = decide(id, approve = false)

    private suspend fun decide(id: String, approve: Boolean) {
        var claimed = false
        _state.update {
            claimed = id !in it.deciding
            if (claimed) it.copy(deciding = it.deciding + id) else it
        }
        if (!claimed) return // a second tap while the first is in flight sends nothing
        var ok = false
        try {
            if (approve) approvalsApi.approve(id) else approvalsApi.deny(id)
            ok = true
        } catch (e: ApiException.NotFound) {
            note("Approval $id expired or was already decided.")
        } catch (e: ApiException) {
            _state.update { it.copy(approvals = linkFor(e)) }
            note(e.message ?: "Approval request failed")
        } finally {
            _state.update { it.copy(deciding = it.deciding - id, pending = if (ok) it.pending.filter { a -> a.id != id } else it.pending) }
        }
        pollApprovals()
        if (ok) {
            send(
                if (approve) "I approved $id. Call the pc tool again with the same args plus confirm:\"$id\"."
                else "I denied $id. Don't retry it."
            )
        }
    }

    suspend fun startLocalAI() {
        if (s.tokenSaverToken.isBlank()) {
            _state.update { it.copy(startOutput = "Add the TokenSaver link token in Settings to start Local AI from here.") }
            return
        }
        _state.update { it.copy(starting = true, startOutput = "Starting Local AI on the PC…") }
        val out = try {
            tokenSaver.run(s.startCommand)
        } catch (e: ApiException) {
            e.message ?: "Couldn't start Local AI"
        }
        _state.update { it.copy(starting = false, startOutput = out.trim().takeLast(600)) }
        refreshModels()
        pollApprovals()
    }
}

/** Fills only blank token fields, so an outside launch can never overwrite tokens already set. */
fun fillEmptyTokens(s: Settings, lm: String?, approval: String?, tokenSaver: String?): Settings = s.copy(
    lmToken = s.lmToken.ifBlank { lm?.trim().orEmpty() },
    approvalToken = s.approvalToken.ifBlank { approval?.trim().orEmpty() },
    tokenSaverToken = s.tokenSaverToken.ifBlank { tokenSaver?.trim().orEmpty() },
)
