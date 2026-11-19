package com.geminipet.android

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var sysTts: TextToSpeech? = TextToSpeech(context, this)
    private var sysReady = false
    private var mediaPlayer: MediaPlayer? = null
    private var pendingSysDone: (() -> Unit)? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = sysTts?.setLanguage(Locale.CHINA)
            sysReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            sysTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    val cb = pendingSysDone
                    pendingSysDone = null
                    if (cb != null) mainHandler.post { cb() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    val cb = pendingSysDone
                    pendingSysDone = null
                    if (cb != null) mainHandler.post { cb() }
                }
            })
        }
    }

    fun cleanText(rawText: String): String {
        return rawText
            .replace(Regex("""\([^)]*\)|（[^）]*）|[♥♡~～★☆💢😠😒🙄🐾✨💛💗💖📸🔊🍗💬▲▼🎙️🎧]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    suspend fun speak(rawText: String, onFinished: (() -> Unit)? = null): String? {
        val spoken = cleanText(rawText)
        if (spoken.isEmpty()) {
            onFinished?.let { mainHandler.post(it) }
            return null
        }

        val engine = PetConfig.getTtsEngine(context)
        return when (engine) {
            TtsEngine.GEMINI -> {
                val err = speakWithGemini(spoken, onFinished)
                if (err != null) {
                    speakWithSystem(spoken, onFinished)
                    err
                } else null
            }
            TtsEngine.OPENAI_COMPAT -> {
                val err = speakWithOpenAiCompat(spoken, onFinished)
                if (err != null) {
                    speakWithSystem(spoken, onFinished)
                    err
                } else null
            }
            TtsEngine.SYSTEM -> {
                speakWithSystem(spoken, onFinished)
                null
            }
        }
    }

    private fun speakWithSystem(spoken: String, onFinished: (() -> Unit)?) {
        if (!sysReady) {
            onFinished?.let { mainHandler.post(it) }
            return
        }
        stopPlayer()
        pendingSysDone = onFinished
        sysTts?.setPitch(PetConfig.getTtsPitch(context))
        sysTts?.setSpeechRate(PetConfig.getTtsRate(context))
        sysTts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "pet_${System.currentTimeMillis()}")
    }

    private suspend fun speakWithGemini(spoken: String, onFinished: (() -> Unit)?): String? = withContext(Dispatchers.IO) {
        val keys = PetConfig.getApiKeyList(context)
        if (keys.isEmpty()) return@withContext "Gemini 语音需要配置 API Key，已回退系统 TTS"
        val key = keys[PetConfig.nextKeyCursor(context, keys.size)]
        val model = PetConfig.getGeminiTtsModel(context).ifEmpty { "gemini-2.5-flash-preview-tts" }
        val voice = PetConfig.getGeminiTtsVoice(context).ifEmpty { "Leda" }
        val traitName = PetConfig.TRAITS.firstOrNull { it.id == PetConfig.getActiveTraitId(context) }?.name ?: "可爱"

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val promptText = "请用自然生动、带点二次元猫娘「$traitName」语气的中文直接朗读下面这句话，不要加额外解释：$spoken"

        val bodyJson = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", promptText)))
            ))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().put(
                    "voiceConfig", JSONObject().put(
                        "prebuiltVoiceConfig", JSONObject().put("voiceName", voice)
                    )
                ))
            })
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-goog-api-key", key)
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext "Gemini TTS HTTP ${resp.code}"
                val root = JSONObject(raw)
                val parts = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                var b64: String? = null
                var mime = ""
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val inline = parts.optJSONObject(i)?.optJSONObject("inlineData")
                        if (inline != null && inline.optString("data").isNotEmpty()) {
                            b64 = inline.optString("data")
                            mime = inline.optString("mimeType")
                            break
                        }
                    }
                }
                if (b64.isNullOrEmpty()) return@withContext "Gemini TTS 未返回音频数据"
                val decoded = Base64.decode(b64, Base64.DEFAULT)
                val isWav = mime.contains("wav", ignoreCase = true) ||
                    (decoded.size >= 4 && decoded[0] == 'R'.code.toByte() && decoded[1] == 'I'.code.toByte())
                val finalBytes = if (isWav) decoded else wrapPcmToWav(decoded, 24000, 1, 16)
                playBytes(finalBytes, "pet_gemini_tts.wav", onFinished)
                null
            }
        } catch (e: Exception) {
            "Gemini TTS 异常: ${e.message}"
        }
    }

    private suspend fun speakWithOpenAiCompat(spoken: String, onFinished: (() -> Unit)?): String? = withContext(Dispatchers.IO) {
        val url = PetConfig.getCustomTtsUrl(context)
        if (url.isEmpty()) return@withContext "未配置自定义 TTS 地址"
        val key = PetConfig.getCustomTtsKey(context)
        val model = PetConfig.getCustomTtsModel(context)
        val voice = PetConfig.getCustomTtsVoice(context)

        val bodyJson = JSONObject().apply {
            put("model", model)
            put("input", spoken)
            put("voice", voice)
            put("response_format", "mp3")
            put("speed", PetConfig.getTtsRate(context).toDouble())
        }

        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (key.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer $key")
        }

        try {
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "自定义 TTS HTTP ${resp.code}"
                val bytes = resp.body?.bytes()
                if (bytes == null || bytes.size < 64) return@withContext "自定义 TTS 返回空音频"
                playBytes(bytes, "pet_custom_tts.mp3", onFinished)
                null
            }
        } catch (e: Exception) {
            "自定义 TTS 异常: ${e.message}"
        }
    }

    private fun playBytes(bytes: ByteArray, fileName: String, onFinished: (() -> Unit)?) {
        stopPlayer()
        val f = File(context.cacheDir, fileName)
        f.writeBytes(bytes)
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(f.absolutePath)
            setOnCompletionListener {
                try { it.release() } catch (_: Exception) {}
                if (mediaPlayer === it) mediaPlayer = null
                onFinished?.let { cb -> mainHandler.post(cb) }
            }
            setOnErrorListener { player, _, _ ->
                try { player.release() } catch (_: Exception) {}
                if (mediaPlayer === player) mediaPlayer = null
                onFinished?.let { cb -> mainHandler.post(cb) }
                true
            }
            prepare()
            start()
        }
        mediaPlayer = mp
    }

    private fun wrapPcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalDataLen = pcm.size + 36
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray())
        writeIntLE(totalDataLen)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(channels)
        writeIntLE(sampleRate)
        writeIntLE(byteRate)
        writeShortLE(blockAlign)
        writeShortLE(bitsPerSample)
        out.write("data".toByteArray())
        writeIntLE(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    fun stopPlayer() {
        pendingSysDone = null
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        try { sysTts?.stop() } catch (_: Exception) {}
    }

    fun release() {
        stopPlayer()
        try { sysTts?.shutdown() } catch (_: Exception) {}
        sysTts = null
    }
}
