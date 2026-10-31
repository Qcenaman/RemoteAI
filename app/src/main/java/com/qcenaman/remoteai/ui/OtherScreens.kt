package com.qcenaman.remoteai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qcenaman.remoteai.UiState
import com.qcenaman.remoteai.data.Settings
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(st: UiState, onOpen: (String) -> Unit, onDelete: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Chats") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        if (st.conversations.isEmpty()) {
            Text("No chats yet.", Modifier.padding(pad).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(st.conversations, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { Text(c.title, maxLines = 1) },
                    supportingContent = {
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.updated)) +
                            if (c.model.isNotBlank()) " · ${c.model}" else "")
                    },
                    trailingContent = { IconButton(onClick = { onDelete(c.id) }) { Icon(Icons.Default.Delete, "Delete") } },
                    modifier = Modifier.clickable { onOpen(c.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(st: UiState, onSave: (Settings) -> Unit, onBack: () -> Unit) {
    val s = st.settings
    var host by remember { mutableStateOf(s.host) }
    var lmPort by remember { mutableStateOf(s.lmPort.toString()) }
    var apPort by remember { mutableStateOf(s.approvalPort.toString()) }
    var tsPort by remember { mutableStateOf(s.tokenSaverPort.toString()) }
    var lmToken by remember { mutableStateOf(s.lmToken) }
    var apToken by remember { mutableStateOf(s.approvalToken) }
    var tsToken by remember { mutableStateOf(s.tokenSaverToken) }
    var cmd by remember { mutableStateOf(s.startCommand) }
    var maxTokens by remember { mutableStateOf(s.maxReplyTokens.toString()) }
    var stopMin by remember { mutableStateOf(s.stopThinkingMinutes.toString()) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Field("PC address (Tailscale IP or name)", host) { host = it }
            Section("LM Studio")
            Field("Port", lmPort, number = true) { lmPort = it }
            Field("API token (RemoteAI)", lmToken, secret = true) { lmToken = it }
            Section("ContextSaver approvals")
            Field("Port", apPort, number = true) { apPort = it }
            Field("Approval token", apToken, secret = true) { apToken = it }
            Section("TokenSaver link (optional, for Start Local AI)")
            Field("Port", tsPort, number = true) { tsPort = it }
            Field("Link token", tsToken, secret = true) { tsToken = it }
            Field("Start command", cmd) { cmd = it }
            Section("Runaway replies")
            Field("Max reply tokens (0 = no limit)", maxTokens, number = true) { maxTokens = it }
            Field("Stop thinking after, minutes (0 = off)", stopMin, number = true) { stopMin = it }
            Button(
                onClick = {
                    onSave(
                        Settings(
                            host = host.trim(),
                            lmPort = lmPort.toIntOrNull() ?: s.lmPort,
                            approvalPort = apPort.toIntOrNull() ?: s.approvalPort,
                            tokenSaverPort = tsPort.toIntOrNull() ?: s.tokenSaverPort,
                            lmToken = lmToken.trim(),
                            approvalToken = apToken.trim(),
                            tokenSaverToken = tsToken.trim(),
                            startCommand = cmd.trim(),
                            maxReplyTokens = maxTokens.trim().toIntOrNull()?.coerceAtLeast(0) ?: s.maxReplyTokens,
                            stopThinkingMinutes = stopMin.trim().toIntOrNull()?.coerceAtLeast(0) ?: s.stopThinkingMinutes,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else if (secret) KeyboardType.Password else KeyboardType.Uri),
    )
}
