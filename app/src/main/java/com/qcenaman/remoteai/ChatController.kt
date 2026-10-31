package com.qcenaman.remoteai

import com.qcenaman.remoteai.data.ChatItem
import com.qcenaman.remoteai.data.Conversation
import com.qcenaman.remoteai.data.ConversationStore
import com.qcenaman.remoteai.data.Settings
import com.qcenaman.remoteai.data.SettingsStore
import com.qcenaman.remoteai.net.ApiException
import com.qcenaman.remoteai.net.Approval
import com.qcenaman.remoteai.net.ApprovalClient
import com.qcenaman.remoteai.net.ChatReply
import com.qcenaman.remoteai.net.LmStudioClient
import com.qcenaman.remoteai.net.ModelInfo
import com.qcenaman.remoteai.net.ReplyStats
import com.qcenaman.remoteai.net.estimateTokens
import com.qcenaman.remoteai.net.StopReason
import com.qcenaman.remoteai.net.Watchdog
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private val s get() = _state.value.settings
    private val chatLock = Mutex()

    /** Per conversation: turns the model never saw complete, because the connection dropped. Sent along with the next message. */
    private val lost = mutableMapOf<String, MutableList<String>>()

    /** Approved while their pc call was blocked on them: they resume in place, unless the call fell back first. */
    private val approvedInPlace = mutableSetOf<String>()

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

    /** Stop button: ends the reply in progress; what arrived is kept, marked (stopped). */
    fun stop() = lm.cancel()

    /** Resends a message as a fresh attempt (the stopped reply never became part of the model's history). */
    suspend fun retry(text: String) = send(text)

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
        val retry = mutableListOf<String>()
        chatLock.withLock {
            saveConv { c ->
                c.copy(
                    title = if (c.items.isEmpty()) input.take(48) else c.title,
                    items = c.items + ChatItem("user", input, stats = ReplyStats(promptTokens = estimateTokens(input), estimated = true)),
                )
            }
            _state.update { it.copy(busy = true) }
            try {
                if (_state.value.model.isBlank() || _state.value.lm != Link.OK) refreshModels()
                val st = _state.value
                when {
                    st.lm == Link.NO_TOKEN -> note("Add the LM Studio token in Settings.")
                    st.lm == Link.BAD_TOKEN -> note("LM Studio rejected the token. Check it in Settings.")
                    st.lm != Link.OK -> {
                        remember(st.conversation.id, "My message: $input")
                        note(st.lmMessage.ifBlank { "Can't reach LM Studio." })
                    }
                    st.model.isBlank() -> note("No model is loaded on the PC. Tap Start Local AI.")
                    else -> {
                        val convId = st.conversation.id
                        val missed = lost[convId].orEmpty()
                        val sent = if (missed.isEmpty()) input else lostContext(missed) + input
                        try {
                            val dog = Watchdog(s.stopThinkingMinutes * 60_000L, clock)
                            val reply = lm.chat(st.model, sent, st.conversation.responseId, s.maxReplyTokens, dog)
                            lost.remove(convId)
                            if (s.maxReplyTokens > 0 && reply.text.isBlank() && reply.toolCalls.isEmpty() && reply.reasoning.isNotBlank()) {
                                // Ran out of tokens while still thinking: keep it on screen only, not in the model's history.
                                saveReply(reply.copy(responseId = null), st.model, stopped = true)
                                stoppedNote(StopReason.TOKEN_LIMIT, input)
                            } else saveReply(reply, st.model)
                            // The 50 s wait ran out before the approval landed: the call fell back to "call again".
                            reply.approvals.map { it.id }.filter { approvedInPlace.remove(it) }.forEach { retry += it }
                        } catch (e: ApiException.Network) {
                            val part = e.partial
                            val record = StringBuilder("My message: ").append(input)
                            if (part != null) {
                                saveReply(part, st.model)
                                part.toolCalls.forEach { record.append("\nYou called ${it.tool} ${it.arguments} → ${it.output.take(300)}") }
                                if (part.text.isNotBlank()) record.append("\nYou had started replying: ${part.text.take(300)}")
                            }
                            remember(convId, record.toString())
                            throw e
                        } catch (e: ApiException.Stopped) {
                            val part = e.partial
                            saveReply(part, st.model, stopped = true)
                            // Tools that ran are real; the thinking and text stay on screen only, so a loop can't carry over.
                            if (part.toolCalls.isNotEmpty()) {
                                remember(convId, "My message: $input\n(The reply was stopped.)" +
                                    part.toolCalls.joinToString("") { "\nYou called ${it.tool} ${it.arguments} → ${it.output.take(300)}" })
                            }
                            if (e.reason != StopReason.USER) stoppedNote(e.reason, input)
                        }
                    }
                }
            } catch (e: ApiException) {
                _state.update { it.copy(lm = linkFor(e), lmMessage = e.message ?: "") }
                note(
                    if (e is ApiException.Network && e.partial != null) "Connection dropped mid-reply (${e.message}). What arrived is kept; your next message tells the model what it missed."
                    else e.message ?: "Request failed"
                )
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
        pollApprovals()
        for (id in retry) send("I approved $id.${approvalDetails(id)} Call the pc tool again with the same args plus confirm:\"$id\".")
    }

    private fun stoppedNote(reason: StopReason, input: String) {
        val why = when (reason) {
            StopReason.REPEATING -> "it kept repeating the same thought"
            StopReason.TOKEN_LIMIT -> "it used ${s.maxReplyTokens} tokens thinking without answering"
            else -> "${s.stopThinkingMinutes} min without doing anything"
        }
        saveConv {
            it.copy(items = it.items + ChatItem("note",
                "Stopped: the model was going in circles ($why). Ask again, or say what to do, e.g. which app or file.",
                retry = input))
        }
    }

    private fun saveReply(reply: ChatReply, model: String, stopped: Boolean = false) {
        if (reply.approvals.isNotEmpty()) {
            _state.update { u -> u.copy(pending = (u.pending + reply.approvals).distinctBy { a -> a.id }) }
        }
        val ctxLen = _state.value.models.firstOrNull { it.id == model }?.contextLength ?: 0
        val stats = reply.stats?.copy(contextLength = ctxLen)
        saveConv { c ->
            val tools = reply.toolCalls.map { ChatItem("tool", it.output, tool = it.tool, args = it.arguments) }
            val text = if (stopped) (reply.text + "\n\n*(stopped)*").trim() else reply.text
            val answer = if (stopped || reply.text.isNotBlank() || reply.reasoning.isNotBlank())
                listOf(ChatItem("assistant", text, reasoning = reply.reasoning, stats = stats)) else emptyList()
            // The prompt's line shows how much context the model read for it.
            val before = if (stats != null && stats.inputTokens > 0) {
                val u = c.items.indexOfLast { it.role == "user" }
                if (u < 0) c.items else c.items.toMutableList().also {
                    val p = it[u].stats ?: ReplyStats()
                    it[u] = it[u].copy(stats = p.copy(inputTokens = stats.inputTokens, contextLength = ctxLen))
                }
            } else c.items
            c.copy(
                items = before + tools + answer,
                responseId = reply.responseId ?: c.responseId,
                model = model,
            )
        }
    }

    private fun remember(convId: String, turn: String) {
        val list = lost.getOrPut(convId) { mutableListOf() }
        if (list.size >= 5) list.removeAt(0)
        list += turn
    }

    private fun lostContext(missed: List<String>) =
        "[The connection dropped, so you never saw these earlier turns complete:\n" +
            missed.joinToString("\n---\n") + "\n]\n\n"

    /** The pc call an approval belongs to, so the retry message works even if the model lost that turn. */
    private fun approvalDetails(id: String): String {
        val call = _state.value.conversation.items.lastOrNull { it.role == "tool" && it.text.contains(id, ignoreCase = true) }
        if (call != null && call.args.isNotBlank()) return " The original call was ${call.tool} ${call.args}."
        val what = _state.value.pending.firstOrNull { it.id == id }?.what.orEmpty()
        return if (what.isNotBlank()) " It was: $what." else ""
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
        val details = approvalDetails(id)
        val st0 = _state.value
        val inPlace = st0.busy && st0.pending.any { it.id == id && it.waiting }
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
        if (ok && inPlace) {
            if (approve) approvedInPlace += id // the blocked call runs (or is refused) inside the current reply
            return
        }
        if (ok) {
            send(
                if (approve) "I approved $id.$details Call the pc tool again with the same args plus confirm:\"$id\"."
                else "I denied $id.$details Don't retry it."
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
