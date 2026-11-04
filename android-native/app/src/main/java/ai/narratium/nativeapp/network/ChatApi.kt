package ai.narratium.nativeapp.network

import ai.narratium.nativeapp.data.CharacterCard
import ai.narratium.nativeapp.data.ChatMessage
import ai.narratium.nativeapp.data.ModelSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * OpenAI-compatible /chat/completions client.
 * No proxy: content is sent directly from this device to the configured HTTPS host.
 */
class ChatApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // Streaming responses can take time.
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    suspend fun stream(
        settings: ModelSettings,
        card: CharacterCard,
        history: List<ChatMessage>,
        onDelta: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(settings.apiKey.isNotBlank()) { "请先在设置里填写 API Key" }
        val base = settings.baseUrl.trim().trimEnd('/')
        require(base.startsWith("https://", ignoreCase = true)) { "仅支持 HTTPS 模型接口" }
        require(settings.model.isNotBlank()) { "模型名称不能为空" }

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", card.systemPrompt()))
            history.takeLast(30).forEach { msg ->
                if (msg.role == "user" || msg.role == "assistant") {
                    put(JSONObject().put("role", msg.role).put("content", msg.content))
                }
            }
        }
        val requestBody = JSONObject()
            .put("model", settings.model)
            .put("messages", messages)
            .put("temperature", settings.temperature.toDouble())
            .put("stream", true)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Accept", "text/event-stream")
            .post(requestBody)
            .build()

        val call = client.newCall(request)
        val job = coroutineContext[kotlinx.coroutines.Job]
        val cancelHandle = job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = response.body?.string()?.take(500) ?: "Unknown error"
                    throw IOException("模型接口返回 HTTP ${response.code}: $reason")
                }
                val stream = response.body ?: throw IOException("模型接口响应为空")
                stream.charStream().buffered().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        currentCoroutineContext().ensureActive()
                        if (line.startsWith("data:")) {
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            if (data.isNotEmpty()) {
                                val delta = runCatching {
                                    val packet = JSONObject(data)
                                    packet.optJSONObject("error")?.let { error ->
                                        throw IOException(error.optString("message", "Model error"))
                                    }
                                    packet.optJSONArray("choices")
                                        ?.optJSONObject(0)
                                        ?.optJSONObject("delta")
                                        ?.optString("content")
                                        .orEmpty()
                                }.getOrElse { failure ->
                                    if (failure is IOException) throw failure
                                    "" // Some compatible providers send SSE status records.
                                }
                                if (delta.isNotEmpty()) onDelta(delta)
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            throw failure
        } finally {
            cancelHandle?.dispose()
            if (job?.isCancelled == true) call.cancel()
        }
    }
}
