package ai.narratium.nativeapp.network

import ai.narratium.nativeapp.data.CharacterCard
import ai.narratium.nativeapp.data.ChatMessage
import ai.narratium.nativeapp.data.ModelSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Direct OpenAI-compatible SSE client, with cancellable OkHttp requests.
 * Content is sent to the selected provider, never to the Narratium website.
 */
class ChatApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE requests are long-lived.
        .build()

    suspend fun stream(
        settings: ModelSettings,
        card: CharacterCard,
        history: List<ChatMessage>,
        onDelta: (String) -> Unit,
    ) {
        require(settings.apiKey.isNotBlank()) { "请先在设置里填写 API Key" }
        require(settings.model.isNotBlank()) { "请填写模型名称" }

        val url = (settings.baseUrl.trim().trimEnd('/') + "/chat/completions")
            .toHttpUrlOrNull() ?: throw IOException("模型接口 URL 无效")
        require(url.isHttps) { "仅支持 HTTPS 模型接口" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "URL 不能包含账号密码" }

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", card.systemPrompt()))
            history.takeLast(30).forEach { msg ->
                if (msg.role == "user" || msg.role == "assistant") {
                    put(JSONObject().put("role", msg.role).put("content", msg.content))
                }
            }
        }
        val body = JSONObject()
            .put("model", settings.model)
            .put("messages", messages)
            .put("temperature", settings.temperature.toDouble())
            .put("stream", true)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        suspendCancellableCoroutine<Unit> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { result ->
                            if (!result.isSuccessful) {
                                val reason = result.body?.string()?.take(500) ?: "Unknown error"
                                throw IOException("模型接口返回 HTTP ${result.code}: $reason")
                            }
                            val streamBody = result.body ?: throw IOException("响应内容为空")
                            streamBody.charStream().buffered().use { reader ->
                                while (continuation.isActive) {
                                    val line = reader.readLine() ?: break
                                    if (!line.startsWith("data:")) continue
                                    val data = line.removePrefix("data:").trim()
                                    if (data == "[DONE]") break
                                    if (data.isEmpty()) continue

                                    val text = try {
                                        val packet = JSONObject(data)
                                        packet.optJSONObject("error")?.let { error ->
                                            throw IOException(error.optString("message", "Model error"))
                                        }
                                        packet.optJSONArray("choices")
                                            ?.optJSONObject(0)
                                            ?.optJSONObject("delta")
                                            ?.optString("content")
                                            .orEmpty()
                                    } catch (failure: org.json.JSONException) {
                                        "" // Some providers send non-content SSE keepalive events.
                                    }
                                    if (text.isNotEmpty() && continuation.isActive) onDelta(text)
                                }
                            }
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }
    }
}
