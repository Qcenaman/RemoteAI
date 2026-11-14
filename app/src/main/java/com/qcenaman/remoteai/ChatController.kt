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
import com.qcenaman.remoteai.net.HUB_PRESETS
import com.qcenaman.remoteai.net.HUB_STARTED
import com.qcenaman.remoteai.net.HubClient
import com.qcenaman.remoteai.net.HubState
import com.qcenaman.remoteai.net.LmStudioClient
import com.qcenaman.remoteai.net.OPENCODE_PASSWORD
import com.qcenaman.remoteai.net.OpenCodeClient
import com.qcenaman.remoteai.net.openCodeLinkCommand
import com.qcenaman.remoteai.net.hubStartCommand
import com.qcenaman.remoteai.net.ModelInfo
import com.qcenaman.remoteai.net.ReplyStats
import com.qcenaman.remoteai.net.estimateTokens
import com.qcenaman.remoteai.net.StopReason
import com.qcenaman.remoteai.net.Watchdog
import com.qcenaman.remoteai.net.TokenSaverClient
import com.qcenaman.remoteai.net.baseUrl
import com.qcenaman.remoteai.net.killCommand
import com.qcenaman.remoteai.net.DESKTOP_QWEN
import com.qcenaman.remoteai.net.LAPTOP_PASSWORD_COMMAND
import com.qcenaman.remoteai.net.LAPTOP_STOP_COMMAND
import com.qcenaman.remoteai.net.OcSession
import com.qcenaman.remoteai.net.Pc
import com.qcenaman.remoteai.net.laptopStartCommand
import com.qcenaman.remoteai.net.pickWindowSession
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
    /** Model being loaded on the PC (label), blank when none. */
    val loadingModel: String = "",
    /** Kill or restart in progress on the PC ("Killing local AI…"), blank when none. */
    val pcAction: String = "",
    /** PC whose OpenCode window this screen follows live; null for normal chats. */
    val pc: Pc? = null,
    val pcConnecting: Boolean = false,
    /** What's going on with the PC link, blank when all is well. */
    val pcStatus: String = "",
    /** The model on the PC is working on the window's chat right now. */
    val pcWorking: Boolean = false,
    /** Laptop only: what else is using its GPU, so the user can stop it or go on anyway. */
    val pcGpuBusy: String = "",
    /** Messages held until the chat's current turn ends, because it runs in the PC's own private window. */
    val pcQueued: List<String> = emptyList(),
)

fun newConv() = Conversation(UUID.randomUUID().toString(), "New chat", System.currentTimeMillis())

/** All app logic, free of Android so it can be tested on the JVM. */
class ChatController(
    private val settingsStore: SettingsStore,
    private val store: ConversationStore,
    lmFactory: ((() -> String), (() -> String)) -> LmStudioClient = { b, t -> LmStudioClient(b, t) },
    approvalFactory: ((() -> String), (() -> String)) -> ApprovalClient = { b, t -> ApprovalClient(b, t) },
    tokenSaverFactory: ((() -> String), (() -> String)) -> TokenSaverClient = { b, t -> TokenSaverClient(b, t) },
    hubFactory: (() -> String) -> HubClient = { b -> HubClient(b) },
    openCodeFactory: ((() -> String), (() -> String)) -> OpenCodeClient = { b, p -> OpenCodeClient(b, p) },
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long Start Local AI waits for LM Studio to answer after starting it. */
    private val startWaitMs: Long = 90_000,
    private val startPollMs: Long = 3_000,
    /** Pause between killing and starting again, so the GPU memory is really free. */
    private val restartGapMs: Long = 3_000,
    private val hubPollMs: Long = 5_000,
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
    private val hub = hubFactory { baseUrl(s.host, s.hubPort) }
    private val openCode = openCodeFactory({ baseUrl(s.host, s.openCodePort) }, { s.openCodePassword })
    private val laptopTokenSaver = tokenSaverFactory({ baseUrl(s.laptopHost, s.laptopTokenSaverPort) }, { s.laptopTokenSaverToken })
    private val laptopOpenCode = openCodeFactory({ baseUrl(s.laptopHost, s.laptopOpenCodePort) }, { s.laptopOpenCodePassword })

    private fun ocFor(pc: Pc) = if (pc == Pc.LAPTOP) laptopOpenCode else openCode

    /** OpenCode permission requests of the followed window: request id -> (session, PC). */
    private val livePending = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Pc>>()

    /** The window's chat the screen shows, and when it last changed. */
    @Volatile private var liveSession: String? = null
    @Volatile private var liveUpdated = -1L
    @Volatile private var liveDir = ""
    /** The window's chat is working in a window with its own private OpenCode, which can't take messages mid-turn. */
    @Volatile private var liveElsewhere = false
    private val liveLock = Mutex()

    /** OpenCode permission requests on screen: request id -> session. */
    private val openCodePending = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** False until OpenCode's tailnet address and password were checked since the app started or a call failed. */
    @Volatile private var openCodeLinked = false

    /** Set by Kill so a hub model still loading stops being waited for. */
    @Volatile private var hubLoadCancelled = false

    init {
        val all = store.all()
        _state.value = UiState(
            settings = settingsStore.load(),
            conversation = all.firstOrNull() ?: newConv(),
            conversations = all,
        )
        _state.update { it.copy(model = it.conversation.model, pc = Pc.of(it.settings.pc)) }
        // Following a PC: open on that PC's last mirrored chat, not on whatever chat was used last.
        _state.value.pc?.let { pc ->
            val last = all.firstOrNull { it.id.startsWith("pc-${pc.key}-") }
            _state.update { it.copy(conversation = last ?: newConv().copy(title = pc.label)) }
        }
    }

    fun saveSettings(new: Settings) {
        settingsStore.save(new)
        _state.update { it.copy(settings = new, lm = Link.UNKNOWN, approvals = Link.UNKNOWN, lmMessage = "") }
    }

    /** Stop button: ends the reply in progress; what arrived is kept, marked (stopped). */
    fun stop() {
        lm.cancel()
        openCode.cancel()
    }

    /** Stop button while following a PC: interrupts the model there, as Esc does in its window. */
    suspend fun stopPc() {
        val pc = _state.value.pc ?: return
        val id = liveSession ?: return
        try { ocFor(pc).interrupt(id) } catch (e: ApiException) { /* it may have just ended */ }
        pollLive()
    }

    /** Resends a message as a fresh attempt (the stopped reply never became part of the model's history). */
    suspend fun retry(text: String) = send(text)

    fun selectModel(id: String) = _state.update { it.copy(model = id) }

    /**
     * Picked in the model list: chats go to it, loading it on the PC first if needed.
     * Other loaded chat models are unloaded so two big models don't fight over the GPU.
     */
    suspend fun chooseModel(key: String): Boolean {
        leavePc()
        val m = _state.value.models.firstOrNull { it.key == key || it.id == key }
        if (m != null && m.loaded) {
            _state.update { it.copy(model = m.id) }
            rememberModel(m.key)
            return true
        }
        if (m?.hub == true) return chooseHubModel(m)
        val label = m?.label ?: key
        _state.update { it.copy(loadingModel = label) }
        return try {
            _state.value.models.filter { it.loaded && it.hub }.forEach { h ->
                try { hub.unload(h.id) } catch (e: ApiException) { /* the hub may already be gone */ }
            }
            _state.value.models.filter { it.loaded && !it.hub && it.key != key }.forEach { lm.unload(it.id) }
            val id = lm.load(m?.key ?: key)
            refreshModels()
            _state.update { st -> st.copy(model = st.models.firstOrNull { it.id == id || it.key == key && it.loaded }?.id ?: id) }
            rememberModel(m?.key ?: key)
            true
        } catch (e: ApiException) {
            _state.update { it.copy(lm = linkFor(e), lmMessage = "Couldn't load $label: ${e.message}") }
            false
        } finally {
            _state.update { it.copy(loadingModel = "") }
        }
    }

    /** Puts a hub model on the PC the way its desktop shortcut does, then waits until it answers. */
    private suspend fun chooseHubModel(m: ModelInfo): Boolean {
        val preset = HUB_PRESETS.first { it.id == m.key }
        if (s.tokenSaverToken.isBlank()) {
            _state.update { it.copy(startOutput = "Add the TokenSaver link token in Settings to load ${m.label} from here.") }
            return false
        }
        hubLoadCancelled = false
        _state.update { it.copy(loadingModel = m.label, startOutput = "") }
        return try {
            for (other in _state.value.models.filter { it.loaded && !it.hub }) {
                try { lm.unload(other.id) } catch (e: ApiException) { /* LM Studio may be closed */ }
            }
            val out = runElevated(hubStartCommand(preset))
            if (!out.contains(HUB_STARTED)) {
                _state.update { it.copy(startOutput = "Couldn't start ${m.label}: ${out.trim().takeLast(400)}") }
                return false
            }
            val until = clock() + preset.loadMinutes * 60_000L
            while (true) {
                val state = try { hub.states()[preset.id] } catch (e: ApiException) { null }
                if (state == HubState.LOADED) break
                val problem = when {
                    hubLoadCancelled -> "Loading ${m.label} was stopped."
                    state == HubState.FAILED -> "${m.label} failed to load on the PC. Open the minimized ELAI hub window to see why."
                    clock() > until -> "${m.label} didn't finish loading within ${preset.loadMinutes} min."
                    else -> null
                }
                if (problem != null) {
                    _state.update { it.copy(startOutput = problem) }
                    return false
                }
                kotlinx.coroutines.delay(hubPollMs)
            }
            refreshModels()
            _state.update { it.copy(model = preset.id) }
            rememberModel(preset.id)
            true
        } catch (e: ApiException) {
            _state.update { it.copy(startOutput = if (e is ApiException.Network) PC_UNREACHABLE else "Couldn't load ${m.label}: ${e.message}") }
            false
        } finally {
            _state.update { it.copy(loadingModel = "") }
        }
    }

    private fun rememberModel(key: String) {
        if (s.lastModel == key) return
        val new = s.copy(lastModel = key)
        settingsStore.save(new)
        _state.update { it.copy(settings = new) }
    }

    /** The model Start Local AI loads: the last one used, else this chat's, else the first on the PC. */
    private fun preferredModel(): String? {
        val ms = _state.value.models
        return listOf(s.lastModel, _state.value.conversation.model)
            .filter { it.isNotBlank() }
            .firstNotNullOfOrNull { want -> ms.firstOrNull { it.key == want || it.id == want }?.key }
            ?: ms.firstOrNull { !it.hub }?.key
    }

    fun newConversation() {
        leavePc()
        _state.update { it.copy(conversation = newConv()) }
    }

    fun openConversation(id: String) {
        val c = store.all().firstOrNull { it.id == id } ?: return
        leavePc()
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
        val hubStates = try { hub.states() } catch (e: ApiException) { emptyMap() }
        val hubModels = HUB_PRESETS.map {
            ModelInfo(it.id, hubStates[it.id] == HubState.LOADED, it.contextLength, name = it.label, hub = true)
        }
        val hubRunning = hubModels.any { it.loaded }
        var lmError: ApiException? = null
        val lmModels = if (s.lmToken.isBlank()) null else try {
            lm.models()
        } catch (e: ApiException) {
            lmError = e
            null
        }
        if (lmModels == null && !hubRunning) {
            val error = lmError
            _state.update { st ->
                st.copy(
                    models = st.models.filter { !it.hub } + hubModels,
                    lm = if (error != null) linkFor(error) else Link.NO_TOKEN,
                    lmMessage = error?.message.orEmpty(),
                )
            }
            return false
        }
        val ms = lmModels.orEmpty() + hubModels
        _state.update { st ->
            val loaded = ms.filter { it.loaded }
            val keep = loaded.any { it.id == st.model }
            st.copy(
                models = ms,
                model = if (keep) st.model else loaded.firstOrNull()?.id ?: "",
                lm = Link.OK,
                lmMessage = if (loaded.isEmpty()) "No model loaded on the PC. Tap the model button to pick one." else "",
                // Retry found everything up: an old Start Local AI output has nothing left to report.
                startOutput = if (loaded.isNotEmpty() && !st.starting) "" else st.startOutput,
            )
        }
        return true
    }

    suspend fun pollApprovals() {
        if (s.approvalToken.isBlank()) {
            _state.update { it.copy(approvals = Link.NO_TOKEN) }
            return
        }
        try {
            val list = approvalsApi.pending()
            _state.update { it.copy(pending = list + it.pending.filter { a -> openCodePending.containsKey(a.id) || livePending.containsKey(a.id) }, approvals = Link.OK) }
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
        if (_state.value.pc != null) return sendToPc(input)
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
                if (_state.value.model.isBlank() || _state.value.lm != Link.OK) {
                    refreshModels()
                    // PC asleep or no model loaded: start LM Studio and load the model before sending.
                    val now = _state.value
                    if (now.lm == Link.OFFLINE || (now.lm == Link.OK && now.models.none { it.loaded })) startLocalAI()
                }
                val st = _state.value
                when {
                    st.lm == Link.NO_TOKEN -> note("Add the LM Studio token in Settings.")
                    st.lm == Link.BAD_TOKEN -> note("LM Studio rejected the token. Check it in Settings.")
                    st.lm != Link.OK -> {
                        remember(st.conversation.id, "My message: $input")
                        note(st.lmMessage.ifBlank { "Can't reach LM Studio." })
                    }
                    st.model.isBlank() -> note("No model is loaded on the PC. Tap the model button to pick one.")
                    st.models.any { it.id == st.model && it.hub } -> sendToOpenCode(st, input)
                    else -> {
                        val convId = st.conversation.id
                        val missed = lost[convId].orEmpty()
                        val sent = if (missed.isEmpty()) input else lostContext(missed) + input
                        try {
                            val dog = Watchdog(s.stopThinkingMinutes * 60_000L, clock)
                            val previous = st.conversation.responseId?.takeUnless { it.startsWith("ses_") }
                            val reply = lm.chat(st.model, sent, previous, s.maxReplyTokens, dog)
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

    private suspend fun linkOpenCode(): Boolean {
        val problem = linkOpenCodeProblem() ?: return true
        note(problem)
        return false
    }

    /** Makes the desktop's OpenCode service reachable and fetches its password; returns what went wrong, or null. */
    private suspend fun linkOpenCodeProblem(): String? {
        if (s.tokenSaverToken.isBlank()) return "Add the TokenSaver link token in Settings to use this model."
        val out = try {
            runElevated(openCodeLinkCommand(s.openCodePort))
        } catch (e: ApiException) {
            return if (e is ApiException.Network) PC_UNREACHABLE else e.message ?: "Couldn't reach OpenCode"
        }
        val password = passwordIn(out)
            ?: return "OpenCode isn't running on the PC and couldn't be started. Open one of its desktop shortcuts once."
        if (password != s.openCodePassword) {
            val new = s.copy(openCodePassword = password)
            settingsStore.save(new)
            _state.update { it.copy(settings = new) }
        }
        openCodeLinked = true
        return null
    }

    /** Hub models run through OpenCode on the PC, with its agent and tools; a chat maps to one OpenCode session. */
    private suspend fun sendToOpenCode(st: UiState, input: String) {
        if (!openCodeLinked && !linkOpenCode()) return
        val agent = "elai-" + st.model
        try {
            val known = st.conversation.responseId?.takeIf { it.startsWith("ses_") }
            val session = known ?: openCode.createSession(agent, st.model, s.projectFolder)
            if (known != null && st.conversation.model != st.model) openCode.switchModel(session, agent, st.model)
            saveConv { it.copy(responseId = session, model = st.model) }
            val reply = openCode.send(session, input) { asked ->
                val ids = asked.map { it.id }.toSet()
                val gone = openCodePending.keys.filter { it !in ids }
                gone.forEach { openCodePending.remove(it) }
                asked.forEach { openCodePending[it.id] = session }
                _state.update { u -> u.copy(pending = u.pending.filter { it.id !in gone } + asked.filter { a -> u.pending.none { it.id == a.id } }) }
            }
            saveReply(reply.copy(responseId = session), st.model)
        } catch (e: ApiException.Stopped) {
            saveReply(e.partial, st.model, stopped = true)
        } catch (e: ApiException) {
            openCodeLinked = false
            note(
                if (e is ApiException.Network) "Lost the connection to OpenCode. It may still be working on the PC; send another message to continue."
                else e.message ?: "OpenCode request failed"
            )
        } finally {
            val mine = openCodePending.keys.toSet()
            openCodePending.clear()
            _state.update { u -> u.copy(pending = u.pending.filter { it.id !in mine }) }
        }
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
            val live = livePending[id]
            val session = openCodePending[id]
            if (live != null) ocFor(live.second).reply(live.first, id, approve)
            else if (session != null) openCode.reply(session, id, approve)
            else if (approve) approvalsApi.approve(id) else approvalsApi.deny(id)
            ok = true
        } catch (e: ApiException.NotFound) {
            note("Approval $id expired or was already decided.")
        } catch (e: ApiException) {
            _state.update { it.copy(approvals = linkFor(e)) }
            note(e.message ?: "Approval request failed")
        } finally {
            _state.update { it.copy(deciding = it.deciding - id, pending = if (ok) it.pending.filter { a -> a.id != id } else it.pending) }
        }
        if (livePending.remove(id) != null) { // OpenCode carries on by itself, the window shows it
            pollLive()
            return
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

    /** Starts LM Studio on the PC (through TokenSaver) if it isn't answering, then makes sure a model is loaded. */
    suspend fun startLocalAI() {
        _state.update { it.copy(starting = true, startOutput = "") }
        try {
            val lmUp = refreshModels()
            if (_state.value.models.none { it.loaded }) {
                val hubLast = HUB_PRESETS.firstOrNull { it.id == s.lastModel }
                if (hubLast != null) {
                    if (chooseModel(hubLast.id)) _state.update { it.copy(startOutput = "", lmMessage = "") }
                    return
                }
            }
            if (!lmUp) {
                // A token problem isn't fixed by starting anything; the banner already says what to do.
                if (_state.value.lm == Link.NO_TOKEN || _state.value.lm == Link.BAD_TOKEN) return
                if (s.tokenSaverToken.isBlank()) {
                    _state.update { it.copy(startOutput = "Add the TokenSaver link token in Settings to start Local AI from here.") }
                    return
                }
                _state.update { it.copy(startOutput = "Starting LM Studio on the PC…") }
                val out = try {
                    tokenSaver.run(s.startCommand)
                } catch (e: ApiException.Network) {
                    // TokenSaver's link runs whenever the PC is on, so not reaching it means the PC itself is out of reach.
                    PC_UNREACHABLE
                } catch (e: ApiException) {
                    e.message ?: "Couldn't start Local AI"
                }
                _state.update { it.copy(startOutput = out.trim().takeLast(600)) }
                // LM Studio's server can take a little while to answer after starting.
                val until = clock() + startWaitMs
                while (!refreshModels() && clock() < until) kotlinx.coroutines.delay(startPollMs)
                if (_state.value.lm != Link.OK) return
            }
            if (_state.value.models.none { it.loaded }) {
                val key = preferredModel()
                if (key == null) {
                    _state.update { it.copy(startOutput = "LM Studio is running but has no chat models downloaded.") }
                    return
                }
                if (!chooseModel(key)) return
            }
            // Everything is up: nothing to report, so the card goes away.
            _state.update { it.copy(startOutput = "", lmMessage = "") }
        } finally {
            _state.update { it.copy(starting = false) }
            pollApprovals()
        }
    }

    /**
     * Kill button: ends every local model and LM Studio on the PC, however they were started.
     * Goes through TokenSaver (elevated), so it works even when LM Studio is hung or not answering.
     */
    suspend fun killLocalAI(): Boolean {
        if (_state.value.pc == Pc.LAPTOP) return stopLaptop()
        _state.update { it.copy(pcAction = "Killing local AI on the PC…", startOutput = "") }
        return try {
            kill()
        } finally {
            _state.update { it.copy(pcAction = "") }
        }
    }

    private suspend fun kill(): Boolean {
        if (s.tokenSaverToken.isBlank()) {
            _state.update { it.copy(startOutput = "Add the TokenSaver link token in Settings to kill or restart Local AI from here.") }
            return false
        }
        hubLoadCancelled = true
        if (_state.value.busy) { // the reply in progress ends as stopped, not as a dropped connection
            lm.cancel()
            openCode.cancel()
        }
        val out = try {
            runElevated(killCommand())
        } catch (e: ApiException) {
            _state.update { it.copy(startOutput = "Couldn't kill Local AI: ${e.message}") }
            return false
        }
        _state.update {
            it.copy(
                startOutput = out.trim().takeLast(600).ifBlank { "Local AI killed." },
                models = it.models.map { m -> m.copy(loaded = false) },
                model = "",
                lm = Link.OFFLINE,
                lmMessage = "",
            )
        }
        return true
    }

    /** Through the admin helper; if that isn't available, runs it normally so the button still does its job. */
    private suspend fun runElevated(command: String): String = try {
        tokenSaver.run(command, elevated = true)
    } catch (e: ApiException.Http) {
        tokenSaver.run(command) + "\n(ran without admin: ${e.message?.take(120)})"
    }

    /** Restart button: kills everything, starts LM Studio again and loads the model that was selected. */
    suspend fun restartLocalAI() {
        if (_state.value.pc == Pc.LAPTOP) {
            if (stopLaptop()) {
                kotlinx.coroutines.delay(restartGapMs)
                selectPc(Pc.LAPTOP)
            }
            return
        }
        val st = _state.value
        val key = st.models.firstOrNull { it.id == st.model }?.key ?: st.model.ifBlank { null } ?: preferredModel()
        _state.update { it.copy(pcAction = "Restarting local AI on the PC…", startOutput = "") }
        try {
            if (!kill()) return
            if (key != null) rememberModel(key) // Start Local AI loads the last model used
            kotlinx.coroutines.delay(restartGapMs)
            startLocalAI()
            // LM Studio may have auto-loaded something else at startup: switch to the selected model.
            val now = _state.value
            if (key != null && now.lm == Link.OK && now.models.none { (it.key == key || it.id == key) && it.loaded }) chooseModel(key)
        } finally {
            _state.update { it.copy(pcAction = "") }
        }
    }

    /** The close button on the status card. */
    fun dismissStartOutput() {
        _state.update { it.copy(startOutput = "", pcGpuBusy = "", pcStatus = if (it.pcConnecting) it.pcStatus else "") }
    }

    private fun setPcSetting(pc: Pc?) {
        val key = pc?.key.orEmpty()
        if (s.pc == key) return
        val new = s.copy(pc = key)
        settingsStore.save(new)
        _state.update { it.copy(settings = new) }
    }

    private fun leavePc() {
        if (_state.value.pc == null) return
        setPcSetting(null)
        liveSession = null
        _state.update { it.copy(pcQueued = emptyList()) }
        val mine = livePending.keys.toSet()
        livePending.clear()
        _state.update { it.copy(pc = null, pcStatus = "", pcWorking = false, pcGpuBusy = "", pending = it.pending.filter { a -> a.id !in mine }) }
    }

    /**
     * Picked in the menu: makes sure the PC's Qwen model and OpenCode are running (start = false only reconnects),
     * then the screen follows the chat in the PC's OpenCode window. gpu: laptop only, "stop-gpu" or "continue"
     * after it reported something else on its GPU.
     */
    suspend fun selectPc(pc: Pc, start: Boolean = true, gpu: String = "ask"): Boolean {
        setPcSetting(pc)
        if (_state.value.pc != pc) {
            liveSession = null
            liveUpdated = -1
            _state.update { it.copy(pc = pc, conversation = newConv().copy(title = pc.label)) }
        }
        _state.update { it.copy(pcConnecting = true, pcGpuBusy = "", pcWorking = false, pcStatus = "Connecting to the ${pc.label}…") }
        val problem = try {
            if (pc == Pc.LAPTOP) connectLaptop(start, gpu) else connectDesktop(start)
        } finally {
            _state.update { it.copy(pcConnecting = false) }
        }
        _state.update { it.copy(pcStatus = problem.orEmpty()) }
        if (problem != null) return false
        liveUpdated = -1
        pollLive()
        return _state.value.pcStatus.isBlank()
    }

    private suspend fun connectDesktop(start: Boolean): String? {
        if (start) {
            refreshModels()
            // Some model of the hub already up (maybe what the window is using): keep it, only start Qwen when none is.
            if (_state.value.models.none { it.hub && it.loaded }) {
                val qwen = _state.value.models.firstOrNull { it.hub && it.key == DESKTOP_QWEN }
                    ?: return "The desktop's Qwen model isn't set up in RemoteAI."
                if (!chooseHubModel(qwen)) return _state.value.startOutput.ifBlank { "Couldn't start ${qwen.label} on the desktop." }
                _state.update { it.copy(startOutput = "") }
            }
        }
        return linkOpenCodeProblem()
    }

    private suspend fun connectLaptop(start: Boolean, gpu: String): String? {
        if (s.laptopTokenSaverToken.isBlank()) return "Add the laptop's TokenSaver token in Settings (Laptop section)."
        // Already running (maybe busy with work): just join it, never restart anything.
        if (gpu == "ask" && laptopAnswers()) return null
        if (start) _state.update { it.copy(pcStatus = "Starting ${Pc.LAPTOP.model} and OpenCode on the laptop…") }
        val out = try {
            laptopTokenSaver.run(if (start) laptopStartCommand(gpu) else LAPTOP_PASSWORD_COMMAND)
        } catch (e: ApiException) {
            return if (e is ApiException.Network) LAPTOP_UNREACHABLE else "Laptop: ${e.message}"
        }
        if (out.lines().any { it.trim() == "GPU-BUSY" }) {
            val what = out.lines().map { it.trim() }.filter { it.isNotBlank() && it != "GPU-BUSY" && !it.startsWith("OCPW:") &&
                !it.startsWith("Job ") && !it.startsWith("\$ ") && !it.startsWith("Last ") && !it.startsWith("Full output") }
            _state.update { it.copy(pcGpuBusy = what.joinToString("\n").ifBlank { "another AI program" }) }
            return "Something else is using the laptop's GPU."
        }
        if (out.contains("MODEL-FAILED") || out.contains("SERVER-FAILED")) return "The laptop couldn't start: " + out.trim().takeLast(400)
        val password = passwordIn(out) ?: return "Couldn't read OpenCode's password on the laptop."
        if (password != s.laptopOpenCodePassword) {
            val new = s.copy(laptopOpenCodePassword = password)
            settingsStore.save(new)
            _state.update { it.copy(settings = new) }
        }
        // The model can take longer to load than one TokenSaver call waits: wait for the server to answer.
        val began = clock()
        val until = began + if (start && !out.lines().any { it.trim() == "READY" }) startWaitMs * 7 else 20_000
        while (true) {
            try {
                laptopOpenCode.sessions(1)
                return null
            } catch (e: ApiException) {
                if (clock() >= until) {
                    return if (start) "OpenCode on the laptop didn't come up. Tap Retry." else
                        "OpenCode isn't running on the laptop. Pick Laptop in the menu to start it."
                }
            }
            if (start) {
                val sec = (clock() - began) / 1000
                _state.update { it.copy(pcStatus = "Starting ${Pc.LAPTOP.model} and OpenCode on the laptop… %d:%02d".format(sec / 60, sec % 60)) }
            }
            kotlinx.coroutines.delay(startPollMs)
        }
    }

    /** True when the laptop's OpenCode server answers with the password the app has (fetching it again if needed). */
    private suspend fun laptopAnswers(): Boolean {
        if (s.laptopOpenCodePassword.isNotBlank()) {
            try { laptopOpenCode.sessions(1); return true } catch (e: ApiException.Network) { return false } catch (e: ApiException) { /* new password */ }
        }
        val out = try { laptopTokenSaver.run(LAPTOP_PASSWORD_COMMAND) } catch (e: ApiException) { return false }
        val password = passwordIn(out) ?: return false
        if (password != s.laptopOpenCodePassword) {
            val new = s.copy(laptopOpenCodePassword = password)
            settingsStore.save(new)
            _state.update { it.copy(settings = new) }
        }
        return try { laptopOpenCode.sessions(1); true } catch (e: ApiException) { false }
    }

    /** Cancel on the status card while connecting. */
    fun connectCancelled() {
        _state.update { it.copy(pcConnecting = false, pcStatus = "Stopped connecting. Pick a PC in the menu to try again.") }
    }

    /** Called every couple of seconds while a PC is followed: mirrors the window's chat and what it's waiting on. */
    suspend fun pollLive() {
        liveLock.withLock { pollLiveLocked() }
    }

    private suspend fun pollLiveLocked() {
        val st0 = _state.value
        val pc = st0.pc ?: return
        if (st0.pcConnecting) return
        val oc = ocFor(pc)
        try {
            val sessions = oc.sessions()
            val active = try { oc.active() } catch (e: ApiException) { emptySet() }
            val win = pickWindowSession(sessions, active)
            if (win == null) {
                _state.update { it.copy(pcWorking = false, pcStatus = "") }
                return
            }
            val working = win.id in active || win.busy
            liveElsewhere = win.busy && win.id !in active
            val queued = _state.value.pcQueued
            if (queued.isNotEmpty() && !working && win.id == liveSession) {
                _state.update { it.copy(pcQueued = emptyList()) }
                queued.forEach { oc.prompt(win.id, it) }
                liveUpdated = -1
                return pollLiveLocked()
            }
            if (win.id != liveSession || win.updated != liveUpdated || working || st0.pcWorking) {
                val items = oc.transcript(win.id)
                if (_state.value.pc != pc) return // switched away meanwhile
                liveSession = win.id
                liveUpdated = win.updated
                liveDir = win.directory
                showLive(pc, win, items + _state.value.pcQueued.map { ChatItem("user", it) } +
                    (if (_state.value.pcQueued.isNotEmpty()) listOf(ChatItem("note", "Held: it's sent when the ${pc.label}'s window finishes this turn.")) else emptyList()))
            }
            val asked = if (working) try { oc.permissions(win.id) } catch (e: ApiException) { emptyList() } else emptyList()
            val ids = asked.map { it.id }.toSet()
            val gone = livePending.keys.filter { it !in ids }
            gone.forEach { livePending.remove(it) }
            asked.forEach { livePending[it.id] = win.id to pc }
            _state.update { u ->
                u.copy(
                    pcWorking = working,
                    pcStatus = "",
                    pending = u.pending.filter { it.id !in gone } + asked.filter { a -> u.pending.none { it.id == a.id } },
                )
            }
        } catch (e: ApiException) {
            if (_state.value.pc != pc) return
            if (e is ApiException.Unauthorized) { // the server restarted with a new password: fetch it again
                if (pc == Pc.DESKTOP) openCodeLinked = false
                val problem = if (pc == Pc.LAPTOP) connectLaptop(start = false, gpu = "ask") else linkOpenCodeProblem()
                if (problem == null) return
            }
            _state.update {
                it.copy(pcWorking = false, pcStatus = when {
                    e is ApiException.Network && pc == Pc.LAPTOP -> "Can't reach OpenCode on the laptop. Pick Laptop in the menu to start it."
                    e is ApiException.Network -> "Can't reach OpenCode on the desktop. Pick Desktop in the menu to start it."
                    else -> "${pc.label}: ${e.message}"
                })
            }
        }
    }

    private fun showLive(pc: Pc, win: OcSession, items: List<ChatItem>) {
        val conv = Conversation(
            id = "pc-${pc.key}-${win.id}",
            title = "${pc.label} · " + win.title.ifBlank { "New chat" },
            updated = System.currentTimeMillis(),
            responseId = win.id,
            model = if (pc == Pc.DESKTOP) _state.value.models.firstOrNull { it.hub && it.loaded }?.id ?: DESKTOP_QWEN else "",
            items = items,
        )
        if (items.isNotEmpty()) store.put(conv)
        _state.update { it.copy(conversation = conv, conversations = store.all()) }
    }

    /** Like typing in the PC's window: the window shows it too, and a busy model gets it at its next turn. */
    private suspend fun sendToPc(input: String) {
        val pc = _state.value.pc ?: return
        val oc = ocFor(pc)
        if (liveElsewhere && _state.value.pcWorking) {
            // Running in the PC's own window: sending now would start a second run of the same chat.
            _state.update { it.copy(pcQueued = it.pcQueued + input) }
            liveUpdated = -1
            pollLive()
            return
        }
        _state.update { st -> st.copy(conversation = st.conversation.copy(items = st.conversation.items + ChatItem("user", input))) }
        try {
            val id = liveSession ?: run {
                val model = if (pc == Pc.DESKTOP) _state.value.models.firstOrNull { it.hub && it.loaded }?.id ?: DESKTOP_QWEN else "coder"
                val dir = liveDir.ifBlank { if (pc == Pc.DESKTOP) s.projectFolder else LAPTOP_PROJECTS }
                oc.createSession("elai-$model", model, dir).also { liveSession = it }
            }
            oc.prompt(id, input)
            liveUpdated = -1
            _state.update { it.copy(pcWorking = true) }
        } catch (e: ApiException) {
            _state.update { st ->
                st.copy(conversation = st.conversation.copy(items = st.conversation.items +
                    ChatItem("note", "Couldn't send to the ${pc.label}: ${if (e is ApiException.Network) "can't reach it" else e.message}", retry = input)))
            }
            return
        }
        pollLive()
    }

    /** Kill on the laptop: stops OpenCode's server and the model there, as closing its shortcut window does. */
    private suspend fun stopLaptop(): Boolean {
        if (s.laptopTokenSaverToken.isBlank()) {
            _state.update { it.copy(pcStatus = "Add the laptop's TokenSaver token in Settings (Laptop section).") }
            return false
        }
        _state.update { it.copy(pcAction = "Stopping the model and OpenCode on the laptop…") }
        return try {
            val out = laptopTokenSaver.run(LAPTOP_STOP_COMMAND)
            _state.update { it.copy(pcWorking = false, pcStatus = if (out.contains("STOPPED")) "Laptop AI stopped. Pick Laptop in the menu to start it again." else out.trim().takeLast(300)) }
            true
        } catch (e: ApiException) {
            _state.update { it.copy(pcStatus = if (e is ApiException.Network) LAPTOP_UNREACHABLE else "Laptop: ${e.message}") }
            false
        } finally {
            _state.update { it.copy(pcAction = "") }
        }
    }
}

/** The password a link command printed; TokenSaver also echoes the command itself, whose "OCPW:\$(cat …" must not count. */
fun passwordIn(out: String): String? =
    OPENCODE_PASSWORD.findAll(out).map { it.groupValues[1] }.lastOrNull { !it.startsWith("\$") && !it.contains("(") }

const val LAPTOP_UNREACHABLE = "Can't reach the laptop: it's off or asleep, not booted into Ubuntu, or Tailscale isn't connected."
const val LAPTOP_PROJECTS = "/home/omegajin/Local Projects"

const val PC_UNREACHABLE = "Can't reach the PC: it's off or asleep, or Tailscale isn't connected on the PC or this phone."

/** Fills only blank token fields, so an outside launch can never overwrite tokens already set. */
fun fillEmptyTokens(s: Settings, lm: String?, approval: String?, tokenSaver: String?, laptopTokenSaver: String? = null): Settings = s.copy(
    laptopTokenSaverToken = s.laptopTokenSaverToken.ifBlank { laptopTokenSaver?.trim().orEmpty() },
    lmToken = s.lmToken.ifBlank { lm?.trim().orEmpty() },
    approvalToken = s.approvalToken.ifBlank { approval?.trim().orEmpty() },
    tokenSaverToken = s.tokenSaverToken.ifBlank { tokenSaver?.trim().orEmpty() },
)
