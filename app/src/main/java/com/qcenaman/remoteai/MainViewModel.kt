package com.qcenaman.remoteai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qcenaman.remoteai.data.EncryptedSettingsStore
import com.qcenaman.remoteai.data.FileConversationStore
import com.qcenaman.remoteai.data.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val controller = ChatController(
        EncryptedSettingsStore(app),
        FileConversationStore(File(app.filesDir, "conversations.json")),
    )
    val state = controller.state
    private var poller: Job? = null

    init {
        // While a reply is on its way, a foreground service keeps the request alive with the screen off.
        viewModelScope.launch {
            state.map { it.busy }.distinctUntilChanged().collect { busy ->
                if (busy) KeepAliveService.start(app) else KeepAliveService.stop(app)
            }
        }
    }

    override fun onCleared() {
        KeepAliveService.stop(getApplication())
        super.onCleared()
    }

    @Volatile private var foreground = false
    private val notified = mutableSetOf<String>()

    /**
     * Polls the approval server while the app is in front, and also in the background while a reply is open:
     * a blocked pc call only waits ~50 s, so an approval must reach the user even with the screen off.
     */
    fun onForeground() {
        foreground = true
        ApprovalNotifier.clear(getApplication())
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            controller.refreshModels()
            while (isActive) {
                controller.pollApprovals()
                val st = state.value
                if (!foreground) {
                    st.pending.filter { it.id !in notified }.forEach {
                        notified += it.id
                        ApprovalNotifier.show(getApplication(), it)
                    }
                    if (!st.busy && st.pending.isEmpty()) break
                }
                delay(if (st.busy || st.pending.isNotEmpty()) 3_000 else 15_000)
            }
        }
    }

    fun onBackground() {
        foreground = false
        if (!state.value.busy) {
            poller?.cancel()
            poller = null
        }
    }

    fun send(text: String) = viewModelScope.launch { controller.send(text) }
    fun retry(text: String) = viewModelScope.launch { controller.retry(text) }
    fun approve(id: String) = viewModelScope.launch { controller.approve(id) }
    fun deny(id: String) = viewModelScope.launch { controller.deny(id) }
    fun refresh() = viewModelScope.launch { controller.refreshModels(); controller.pollApprovals() }
    fun startLocalAI() = viewModelScope.launch { controller.startLocalAI() }
    fun saveSettings(s: Settings) {
        controller.saveSettings(s)
        refresh()
    }
}
