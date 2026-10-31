package com.qcenaman.remoteai.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class ApiException(message: String) : Exception(message) {
    class Unauthorized(val server: String) : ApiException("$server: token wrong or missing")
    class Http(val server: String, val code: Int, body: String) : ApiException("$server: HTTP $code ${body.take(300)}")
    class Network(val server: String, cause: IOException) : ApiException("$server unreachable: ${cause.message ?: cause.javaClass.simpleName}")
    class NotFound(val server: String) : ApiException("$server: not found")
}

private val jsonType = "application/json".toMediaType()

fun baseUrl(host: String, port: Int): String {
    val h = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    val bracketed = if (h.contains(':') && !h.startsWith("[")) "[$h]" else h
    return "http://$bracketed:$port"
}

open class HttpBase(protected val server: String, protected val http: OkHttpClient) {
    protected suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { r ->
                val body = r.body?.string() ?: ""
                when {
                    r.code == 401 -> throw ApiException.Unauthorized(server)
                    r.code == 404 -> throw ApiException.NotFound(server)
                    !r.isSuccessful -> throw ApiException.Http(server, r.code, body)
                    else -> body
                }
            }
        } catch (e: IOException) {
            throw ApiException.Network(server, e)
        }
    }

    protected fun Request.Builder.auth(token: String): Request.Builder =
        if (token.isBlank()) this else header("Authorization", "Bearer ${token.trim()}")
}

fun defaultHttp(readSeconds: Long): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(readSeconds, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

/** LM Studio's REST API; ContextSaver runs as the mcp/contextsaver plugin inside it. */
class LmStudioClient(
    private val base: () -> String,
    private val token: () -> String,
    http: OkHttpClient = defaultHttp(900),
) : HttpBase("LM Studio", http) {

    suspend fun models(): List<ModelInfo> =
        parseModels(call(Request.Builder().url(base() + "/api/v0/models").auth(token()).get().build()))

    suspend fun chat(model: String, input: String, previousResponseId: String?): ChatReply {
        val body = buildJsonObject {
            put("model", model)
            put("input", input)
            put("integrations", JsonArray(listOf(JsonPrimitive("mcp/contextsaver"))))
            if (!previousResponseId.isNullOrBlank()) put("previous_response_id", previousResponseId)
        }.toString()
        val req = Request.Builder().url(base() + "/api/v1/chat").auth(token())
            .post(body.toRequestBody(jsonType)).build()
        return parseChat(call(req))
    }
}

/** ContextSaver's approval server: the only way a risky pc call gets approved. */
class ApprovalClient(
    private val base: () -> String,
    private val token: () -> String,
    http: OkHttpClient = defaultHttp(15),
) : HttpBase("Approvals", http) {

    suspend fun pending(): List<Approval> =
        parseApprovals(call(Request.Builder().url(base() + "/approvals").auth(token()).get().build()))

    suspend fun approve(id: String) = decide(id, "approve")
    suspend fun deny(id: String) = decide(id, "deny")

    private suspend fun decide(id: String, what: String): String {
        val req = Request.Builder().url(base() + "/approvals/$id/$what").auth(token())
            .post(ByteArray(0).toRequestBody(null)).build()
        return call(req)
    }
}

/** TokenSaver's link (tokensaver serve): used only to start Local AI when LM Studio is down. */
class TokenSaverClient(
    private val base: () -> String,
    private val token: () -> String,
    http: OkHttpClient = defaultHttp(200),
) : HttpBase("TokenSaver", http) {

    suspend fun run(command: String): String {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "run")
                putJsonObject("arguments") {
                    put("command", command)
                    put("wait_seconds", 150)
                    put("tail", 8)
                }
            }
        }.toString()
        val req = Request.Builder().url(base() + "/mcp").auth(token()).post(body.toRequestBody(jsonType)).build()
        val (text, isError) = parseMcpText(call(req))
        if (isError) throw ApiException.Http(server, 200, text)
        return text
    }
}
