package com.qcenaman.remoteai.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qcenaman.remoteai.Link
import com.qcenaman.remoteai.UiState
import com.qcenaman.remoteai.data.ChatItem
import com.qcenaman.remoteai.net.Approval
import com.qcenaman.remoteai.net.promptLine
import com.qcenaman.remoteai.net.replyLine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    st: UiState,
    onSend: (String) -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onModel: (String) -> Unit,
    onNewChat: () -> Unit,
    onConversations: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onStartLocalAI: () -> Unit,
    onStop: () -> Unit,
    onRetry: (String) -> Unit,
    onDismissStatus: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(st.conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (st.conversation.items.isNotEmpty()) {
                        CopyButton(conversationText(st.conversation.title, st.conversation.items), "Chat copied",
                            LocalContentColor.current, size = 48)
                    }
                    IconButton(onClick = onConversations) { Icon(Icons.Default.History, "Chats") }
                    IconButton(onClick = onNewChat) { Icon(Icons.Default.Add, "New chat") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
        ) {
            ModelRow(st, onModel, onRefresh)
            StatusBanner(st, onRefresh, onStartLocalAI, onSettings, onDismissStatus)
            st.pending.forEach { a -> ApprovalCard(a, a.id in st.deciding, onApprove, onDeny) }

            val list = rememberLazyListState()
            LaunchedEffect(st.conversation.items.size, st.conversation.id) {
                if (st.conversation.items.isNotEmpty()) list.animateScrollToItem(st.conversation.items.size - 1)
            }
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (st.conversation.items.isEmpty()) {
                    item {
                        Text(
                            "Ask your local AI anything. It can use your PC through ContextSaver; risky actions wait for your Approve.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                val lastRetry = st.conversation.items.indexOfLast { it.retry.isNotBlank() }
                    .takeIf { it == st.conversation.items.lastIndex }
                itemsIndexed(st.conversation.items) { i, item ->
                    ItemView(item, st.conversation.title, onRetry.takeIf { i == lastRetry && !st.busy })
                }
            }
            if (st.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            InputBar(busy = st.busy, onSend = onSend, onStop = onStop)
        }
    }
}

@Composable
private fun ModelRow(st: UiState, onModel: (String) -> Unit, onRefresh: () -> Unit) {
    val loaded = st.models.filter { it.loaded }
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AssistChip(
                onClick = { if (loaded.size > 1) open = true else onRefresh() },
                label = {
                    Text(
                        st.model.ifBlank { "No model loaded" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 260.dp),
                    )
                },
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                loaded.forEach { m ->
                    DropdownMenuItem(text = { Text(m.id) }, onClick = { onModel(m.id); open = false })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
    }
}

@Composable
private fun StatusBanner(st: UiState, onRefresh: () -> Unit, onStart: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val lmMsg = when (st.lm) {
        Link.NO_TOKEN -> "LM Studio token missing."
        Link.BAD_TOKEN -> "LM Studio token wrong or missing."
        Link.OFFLINE -> "LM Studio isn't answering. Is the PC on, with Tailscale and Local AI running?"
        Link.ERROR -> st.lmMessage
        else -> if (st.lm == Link.OK && st.model.isBlank()) "No model loaded on the PC." else ""
    }
    val apMsg = when (st.approvals) {
        Link.NO_TOKEN -> "Approval token missing: risky actions can't be approved."
        Link.BAD_TOKEN -> "Approval token wrong or missing."
        Link.OFFLINE -> "Approval server isn't answering (it runs while Local AI is started)."
        else -> ""
    }
    val tokenProblem = st.lm == Link.NO_TOKEN || st.lm == Link.BAD_TOKEN ||
        st.approvals == Link.NO_TOKEN || st.approvals == Link.BAD_TOKEN
    val canStart = st.lm == Link.OFFLINE || (st.lm == Link.OK && st.model.isBlank())
    if (lmMsg.isBlank() && apMsg.isBlank() && st.startOutput.isBlank() && !st.starting) return
    val errorText = listOf(lmMsg, apMsg, st.startOutput).filter { it.isNotBlank() }.joinToString("\n\n")
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(lmMsg, apMsg).filter { it.isNotBlank() }.forEach {
                Text(it, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            }
            if (st.startOutput.isNotBlank()) {
                Text(st.startOutput, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (tokenProblem) TextButton(onClick = onSettings) { Text("Settings") }
                TextButton(onClick = onRefresh) { Text("Retry") }
                if (canStart || st.starting) {
                    Button(onClick = onStart, enabled = !st.starting) {
                        if (st.starting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("Start Local AI")
                    }
                }
                Spacer(Modifier.weight(1f))
                if (errorText.isNotBlank()) CopyButton(errorText, "Error copied", MaterialTheme.colorScheme.onErrorContainer)
                if (st.startOutput.isNotBlank() && !st.starting) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, "Close", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(a: Approval, deciding: Boolean, onApprove: (String) -> Unit, onDeny: (String) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.size(8.dp))
                Text(if (a.waiting) "Approve now? The PC is waiting (${a.id})" else "Approve on your PC? (${a.id})", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.weight(1f))
                CopyButton("Approval ${a.id}: ${a.what}", "Approval copied", MaterialTheme.colorScheme.onTertiaryContainer)
            }
            SelectionContainer {
                Text(a.what, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onApprove(a.id) }, enabled = !deciding) { Text("Approve") }
                OutlinedButton(onClick = { onDeny(a.id) }, enabled = !deciding) { Text("Deny") }
            }
        }
    }
}

@Composable
private fun ItemView(item: ChatItem, title: String, onRetry: ((String) -> Unit)?) {
    when (item.role) {
        "user" -> Column(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                SelectionContainer {
                    Text(item.text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                item.stats?.let { promptLine(it) }?.takeIf { it.isNotBlank() }?.let { StatsLine(it, Modifier.weight(1f), end = true) }
                CopyButton(item.text, "Message copied")
            }
        }
        "assistant" -> Column(Modifier.fillMaxWidth()) {
            if (item.reasoning.isNotBlank()) Thinking(item.reasoning, title)
            if (item.text.isNotBlank()) SelectionContainer { MarkdownText(item.text, Modifier.padding(4.dp)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val line = item.stats?.let { replyLine(it) }.orEmpty()
                if (line.isNotBlank()) StatsLine(line, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                if (item.text.isNotBlank()) CopyButton(item.text, "Reply copied")
            }
        }
        "tool" -> ToolView(item)
        else -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f).padding(4.dp),
                )
                CopyButton(item.text, "Error copied", MaterialTheme.colorScheme.error)
            }
            if (onRetry != null && item.retry.isNotBlank()) {
                OutlinedButton(onClick = { onRetry(item.retry) }, modifier = Modifier.padding(start = 4.dp)) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Retry")
                }
            }
        }
    }
}

/** Copies text so it can be pasted elsewhere, instead of taking a screenshot. */
@Composable
private fun CopyButton(
    text: String,
    toast: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Int = 36,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    IconButton(onClick = {
        clipboard.setText(AnnotatedString(text.trim()))
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }, modifier = Modifier.size(size.dp)) {
        Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(if (size > 40) 22.dp else 18.dp), tint = tint)
    }
}

/** Small always-visible line of tokens, speed and context under a message. */
@Composable
private fun StatsLine(text: String, modifier: Modifier = Modifier, end: Boolean = false) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Tapping the numbers copies them.
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (end) TextAlign.End else TextAlign.Start,
        modifier = modifier.clickable {
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, "Stats copied", Toast.LENGTH_SHORT).show()
        }.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun ToolView(item: ChatItem) {
    var open by rememberSaveable { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().animateContentSize().clickable { open = !open },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Build, null, Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(
                    item.tool.ifBlank { "tool" } + "  " + item.args,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (open) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                CopyButton(item.tool.ifBlank { "tool" } + " " + item.args + "\n\n" + item.text, "Tool call copied")
            }
            if (open && item.text.isNotBlank()) {
                SelectionContainer {
                    Text(item.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun Thinking(text: String, title: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(thinkingFileText(title, text).toByteArray()) }
                ?: error("no stream")
        }.isSuccess
        Toast.makeText(context, if (ok) "Thinking saved" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
    }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (open) "▾ " else "▸ ") + "Thinking",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).clickable { open = !open }.padding(4.dp),
            )
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(text.trim()))
                Toast.makeText(context, "Thinking copied", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ContentCopy, "Copy thinking", Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { save.launch(thinkingFileName(title)) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Save, "Save thinking as text", Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (open) SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        }
    }
}

@Composable
private fun InputBar(busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (!busy) "Message" else "Waiting for the reply…") },
            maxLines = 6,
        )
        Spacer(Modifier.size(8.dp))
        if (busy) {
            FilledIconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Stop") }
        } else {
            FilledIconButton(
                onClick = { onSend(text); text = "" },
                enabled = text.isNotBlank(),
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}
