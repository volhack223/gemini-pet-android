package com.geminipet.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

data class PetReplyResult(
    val ok: Boolean,
    val mood: String = "xd",
    val text: String = "",
    val error: String = "",
    val latencyMs: Long = 0L
)

object LlmClient {
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun isLocalUrl(url: String): Boolean {
        return try {
            val host = URI(url.trim()).host?.lowercase() ?: return false
            host == "localhost" || host == "127.0.0.1" || host.startsWith("192.168.") || host.startsWith("10.")
        } catch (_: Exception) {
            false
        }
    }

    suspend fun testConnection(ctx: Context): PetReplyResult = withContext(Dispatchers.IO) {
        val sys = "你是一只可爱的猫娘桌宠。"
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", sys))
            put(JSONObject().put("role", "user").put("content", "请只回复两个字：喵呜"))
        }
        callWithRotation(ctx, messages, maxTokens = 128, temperature = 0.7)
    }

    suspend fun generateReaction(
        ctx: Context,
        promptText: String,
        screenshotDataUrl: String?
    ): PetReplyResult = withContext(Dispatchers.IO) {
        val sys = buildString {
            appendLine(PetConfig.buildPersonaPrompt(ctx))
            appendLine()
            appendLine("【输出格式要求】")
            appendLine("- 句首必须带一个最贴合情绪的表情标签，从以下 14 个中选一个：[idle]、[eat]、[xd]、[grin]、[angry]、[smug]、[shy]、[love]、[sleep]、[cry]、[shock]、[disgust]、[think]、[dizzy]。")
            appendLine("- 标签后紧跟一句 12~34 字的口语化台词，不加引号和解释。")
        }

        val userContent: Any = if (!screenshotDataUrl.isNullOrBlank()) {
            JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", "$promptText\n（已附带手机实时屏幕截图，请优先结合画面内容吐槽或互动！）"))
                put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", screenshotDataUrl)
                    )
                )
            }
        } else {
            promptText
        }

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", sys))
            put(JSONObject().put("role", "user").put("content", userContent))
        }

        var res = callWithRotation(ctx, messages, maxTokens = 512, temperature = 0.98)
        if (!res.ok && !screenshotDataUrl.isNullOrBlank() && Regex("HTTP 400|HTTP 422|vision|image", RegexOption.IGNORE_CASE).containsMatchIn(res.error)) {
            val fallbackMessages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sys))
                put(JSONObject().put("role", "user").put("content", promptText))
            }
            res = callWithRotation(ctx, fallbackMessages, maxTokens = 512, temperature = 0.98)
        }

        parseMoodReply(ctx, res)
    }

    /**
     * 原生语音对话：直接把麦克风录制的 M4A 音频发给 Gemini 多模态接口，一步完成听写+回复，不依赖手机自带 Google 语音服务
     */
    suspend fun generateVoiceChat(
        ctx: Context,
        audioBase64: String,
        mimeType: String = "audio/mp4"
    ): PetReplyResult = withContext(Dispatchers.IO) {
        val keys = PetConfig.getApiKeyList(ctx)
        if (keys.isEmpty()) return@withContext PetReplyResult(ok = false, error = "语音对话需要配置 Gemini API Key")
        val model = PetConfig.getModel(ctx).ifBlank { "gemini-3.5-flash-lite" }
        val sys = buildString {
            appendLine(PetConfig.buildPersonaPrompt(ctx))
            appendLine()
            appendLine("【语音对话要求】")
            appendLine("- 请认真听懂用户发来的这段语音，直接用当前人设自然回复对方（15~45 字以内，适合语音播报）。")
            appendLine("- 句首必须带一个最贴合情绪的表情标签：[idle]、[eat]、[xd]、[grin]、[angry]、[smug]、[shy]、[love]、[sleep]、[cry]、[shock]、[disgust]、[think]、[dizzy]。")
            appendLine("- 不要复述“你刚才说的是…”，直接像面对面聊天一样回应。")
        }

        val start = PetConfig.nextKeyCursor(ctx, keys.size)
        var lastErr = "语音请求失败"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

        val bodyJson = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().apply {
                    put(JSONObject().put("text", "请听下面这段用户的语音并立刻回应："))
                    put(JSONObject().put("inlineData", JSONObject().apply {
                        put("mimeType", mimeType)
                        put("data", audioBase64)
                    }))
                })
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.92)
                put("maxOutputTokens", 256)
            })
        }

        for (attempt in keys.indices) {
            val idx = (start + attempt) % keys.size
            val key = keys[idx]
            val t0 = System.currentTimeMillis()
            val req = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", key)
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
                .build()
            try {
                httpClient.newCall(req).execute().use { resp ->
                    val latency = System.currentTimeMillis() - t0
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val msg = try {
                            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
                        } catch (_: Exception) { "" }
                        lastErr = "语音 HTTP ${resp.code}" + if (msg.isNotEmpty()) ": ${msg.take(70)}" else ""
                        if (resp.code in listOf(401, 403, 429, 500, 502, 503) && attempt < keys.size - 1) {
                            return@use
                        }
                        return@withContext PetReplyResult(ok = false, error = lastErr, latencyMs = latency)
                    }
                    val parts = JSONObject(raw)
                        .optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                    val sb = StringBuilder()
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val t = parts.optJSONObject(i)?.optString("text").orEmpty()
                            if (t.isNotEmpty()) sb.append(t)
                        }
                    }
                    val content = sb.toString().trim()
                    if (content.isEmpty()) {
                        return@withContext PetReplyResult(ok = false, error = "没听清刚才说了什么~", latencyMs = latency)
                    }
                    return@withContext parseMoodReply(ctx, PetReplyResult(ok = true, text = content, latencyMs = latency))
                }
            } catch (e: Exception) {
                lastErr = e.message ?: "语音网络异常"
                if (attempt == keys.size - 1) {
                    return@withContext PetReplyResult(ok = false, error = lastErr)
                }
            }
        }
        PetReplyResult(ok = false, error = lastErr)
    }

    private fun parseMoodReply(ctx: Context, res: PetReplyResult): PetReplyResult {
        if (!res.ok) return res
        val cleaned = res.text.replace(Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE), "").trim()
        val m = Regex("""^\[([a-z]+)\]\s*(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(cleaned)
        val mood = if (m != null && PetConfig.SPRITE_RES_MAP.containsKey(m.groupValues[1].lowercase())) {
            m.groupValues[1].lowercase()
        } else {
            "xd"
        }
        val finalText = if (m != null) m.groupValues[2].trim() else cleaned.replace(Regex("""^\[.*?\]\s*"""), "").trim()
        if (finalText.isNotEmpty()) {
            PetConfig.addRecentReply(ctx, finalText)
        }
        return res.copy(mood = mood, text = finalText.ifEmpty { "喵呜~" })
    }

    suspend fun chat(ctx: Context, userMessage: String): PetReplyResult = withContext(Dispatchers.IO) {
        val sys = PetConfig.buildPersonaPrompt(ctx)
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", sys))
            put(JSONObject().put("role", "user").put("content", userMessage))
        }
        val res = callWithRotation(ctx, messages, maxTokens = 1024, temperature = 0.82)
        if (!res.ok) return@withContext res
        val cleaned = res.text.replace(Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE), "").trim()
        res.copy(text = cleaned.ifEmpty { res.text })
    }

    private fun callWithRotation(
        ctx: Context,
        messages: JSONArray,
        maxTokens: Int,
        temperature: Double
    ): PetReplyResult {
        val apiUrl = PetConfig.getApiUrl(ctx)
        if (apiUrl.isBlank()) return PetReplyResult(ok = false, error = "请先填写 API 接口地址")
        val model = PetConfig.getModel(ctx).ifBlank { "gemini-3.5-flash-lite" }
        val keys = PetConfig.getApiKeyList(ctx)
        val local = isLocalUrl(apiUrl)
        if (!local && keys.isEmpty()) return PetReplyResult(ok = false, error = "请先填写 API Key（本地模型可留空）")

        val tryKeys = if (keys.isNotEmpty()) keys else listOf("")
        val start = PetConfig.nextKeyCursor(ctx, tryKeys.size)
        val isGoogle = apiUrl.contains("googleapis.com", ignoreCase = true)
        var lastErr = "请求失败"

        for (attempt in tryKeys.indices) {
            val idx = (start + attempt) % tryKeys.size
            val key = tryKeys[idx]
            val t0 = System.currentTimeMillis()
            val bodyJson = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", maxTokens)
                put("temperature", temperature)
                put("top_p", 0.95)
                if (isGoogle && Regex("""gemini-2\.5""", RegexOption.IGNORE_CASE).containsMatchIn(model)) {
                    put("reasoning_effort", "none")
                }
            }
            val reqBuilder = Request.Builder()
                .url(apiUrl)
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
                .header("Content-Type", "application/json")
            if (key.isNotEmpty()) {
                reqBuilder.header("Authorization", "Bearer $key")
            }

            try {
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val latency = System.currentTimeMillis() - t0
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val msg = try {
                            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                        lastErr = "HTTP ${resp.code}" + if (msg.isNotEmpty()) ": ${msg.take(80)}" else ""
                        if (resp.code in listOf(401, 403, 429, 500, 502, 503) && attempt < tryKeys.size - 1) {
                            return@use
                        }
                        return PetReplyResult(ok = false, error = lastErr, latencyMs = latency)
                    }
                    val content = JSONObject(raw)
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        ?.trim()
                        .orEmpty()
                    if (content.isEmpty()) {
                        lastErr = "模型返回空内容"
                        return PetReplyResult(ok = false, error = lastErr, latencyMs = latency)
                    }
                    return PetReplyResult(ok = true, text = content, latencyMs = latency)
                }
            } catch (e: Exception) {
                lastErr = e.message ?: "网络请求异常"
                if (attempt == tryKeys.size - 1) {
                    return PetReplyResult(ok = false, error = lastErr)
                }
            }
        }
        return PetReplyResult(ok = false, error = lastErr)
    }
}
