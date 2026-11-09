package com.qcenaman.remoteai.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A model of the desktop's ELAI hub (llama-server on port 8080): the ones with Cline and OpenCode shortcuts. */
data class HubPreset(val id: String, val label: String, val preset: String, val contextLength: Int, val loadMinutes: Int)

val HUB_PRESETS = listOf(
    HubPreset("coder", "Qwen3.8-27B Coder", "D:\\ELAI\\launchers\\coder.ini", 131072, 10),
    HubPreset("deepseek", "DeepSeek V4.1 Flash", "D:\\ELAI\\launchers\\deepseek-64k.ini", 65536, 60),
    HubPreset("ripple", "Ripple 65B Preview", "D:\\ELAI\\launchers\\ripple-64k.ini", 65536, 15),
)

const val HUB_STARTED = "Hub started"

/** Replaces whatever the hub is serving with this model's preset, the way its desktop shortcut does. */
fun hubStartScript(p: HubPreset): String = """
§ErrorActionPreference = 'SilentlyContinue'
Get-Process llama-server | Stop-Process -Force
Start-Sleep -Seconds 2
Get-Process cmd | Where-Object { §_.MainWindowTitle -like 'ELAI hub*' } | Stop-Process -Force
if (Get-Process llama-server) { 'Could not stop the running llama-server.'; exit 1 }
Start-Process cmd.exe -WorkingDirectory 'D:\ELAI\llama' -WindowStyle Minimized -ArgumentList '/c title ELAI hub (${p.label}) - close this window to stop the model & "D:\ELAI\llama\llama-server.exe" --models-preset "${p.preset}" --models-max 1 --host 0.0.0.0 --port 8080 --metrics || pause'
'$HUB_STARTED'
""".trimIndent().replace('§', '$')

fun hubStartCommand(p: HubPreset): String = encodedPowerShell(hubStartScript(p))

enum class HubState { LOADED, LOADING, UNLOADED, FAILED }

private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

/** GET /models of the hub: model id -> state. */
fun parseHubStates(body: String): Map<String, HubState> {
    val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyMap()
    val arr = root["data"] as? JsonArray ?: return emptyMap()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val status = o["status"] as? JsonObject
        val value = status?.get("value").text()
        val state = when {
            status?.get("failed").text() == "true" || value.contains("fail") || value.contains("error") -> HubState.FAILED
            value == "loaded" -> HubState.LOADED
            value == "loading" -> HubState.LOADING
            else -> HubState.UNLOADED
        }
        o["id"].text().takeIf { it.isNotBlank() }?.let { it to state }
    }.toMap()
}

private val hubJson = "application/json".toMediaType()

/** The ELAI hub's llama-server router: which model is up, and freeing the GPU. Chats go through OpenCode. */
class HubClient(
    private val base: () -> String,
    private val quick: OkHttpClient = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build(),
) : HttpBase("Model hub", quick) {

    /** Model id -> state; throws when the hub isn't running. Never retries, so a refresh stays quick. */
    suspend fun states(): Map<String, HubState> = withContext(Dispatchers.IO) {
        try {
            quick.newCall(Request.Builder().url(base() + "/models").get().build()).execute().use { r ->
                checkStatus(r)
                parseHubStates(r.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw ApiException.Network(server, e)
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
    }

    /** Frees the GPU; the hub keeps running. */
    suspend fun unload(id: String) {
        val body = buildJsonObject { put("model", id) }.toString()
        call(Request.Builder().url(base() + "/models/unload").post(body.toRequestBody(hubJson)).build())
    }
}
