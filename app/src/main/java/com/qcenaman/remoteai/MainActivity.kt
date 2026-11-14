package com.qcenaman.remoteai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qcenaman.remoteai.ui.ChatScreen
import com.qcenaman.remoteai.ui.ConversationsScreen
import com.qcenaman.remoteai.ui.SettingsScreen

enum class Screen { CHAT, CONVERSATIONS, SETTINGS }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        provision(intent)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { AppTheme { App(vm) } }
    }

    /** Lets the PC refill tokens after a reinstall (am start ... --es lmToken ...). Only fills empty fields. */
    private fun provision(i: android.content.Intent?) {
        val st = vm.state.value.settings
        val filled = fillEmptyTokens(
            st,
            i?.getStringExtra("lmToken"),
            i?.getStringExtra("approvalToken"),
            i?.getStringExtra("tokenSaverToken"),
            i?.getStringExtra("laptopTokenSaverToken"),
        )
        if (filled != st) vm.saveSettings(filled)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        provision(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: MainViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    var screen by remember {
        mutableStateOf(if (st.settings.lmToken.isBlank()) Screen.SETTINGS else Screen.CHAT)
    }
    BackHandler(enabled = screen != Screen.CHAT) { screen = Screen.CHAT }
    when (screen) {
        Screen.CHAT -> ChatScreen(
            st = st,
            onSend = { vm.send(it) },
            onApprove = { vm.approve(it) },
            onDeny = { vm.deny(it) },
            onModel = { vm.chooseModel(it) },
            onNewChat = { vm.controller.newConversation() },
            onConversations = { screen = Screen.CONVERSATIONS },
            onSettings = { screen = Screen.SETTINGS },
            onRefresh = { vm.refresh() },
            onStartLocalAI = { vm.startLocalAI() },
            onKillLocalAI = { vm.killLocalAI() },
            onRestartLocalAI = { vm.restartLocalAIThenFollow() },
            onStop = { vm.stop() },
            onPc = { vm.selectPc(it) },
            onPcGpu = { pc, how -> vm.selectPc(pc, how) },
            onCancelPc = { vm.cancelConnect() },
            onRetry = { vm.retry(it) },
            onDismissStatus = { vm.dismissStatus() },
        )
        Screen.CONVERSATIONS -> ConversationsScreen(
            st = st,
            onOpen = { vm.controller.openConversation(it); screen = Screen.CHAT },
            onDelete = { vm.controller.deleteConversation(it) },
            onBack = { screen = Screen.CHAT },
        )
        Screen.SETTINGS -> SettingsScreen(
            st = st,
            onSave = { vm.saveSettings(it); screen = Screen.CHAT },
            onBack = { screen = Screen.CHAT },
        )
    }
}
