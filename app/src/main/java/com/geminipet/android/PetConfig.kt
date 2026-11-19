package com.geminipet.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class OverlayMode(val code: String, val label: String) {
    CAPSULE("capsule", "状态栏灵动胶囊"),
    FLOATING("floating", "悬浮小人"),
    HIDDEN("hidden", "贴边隐身");

    companion object {
        fun fromCode(code: String?): OverlayMode =
            entries.firstOrNull { it.code == code } ?: CAPSULE
    }
}

enum class TtsEngine(val code: String, val label: String) {
    GEMINI("gemini", "Gemini AI 语音"),
    OPENAI_COMPAT("openai_compat", "自定义在线 TTS"),
    SYSTEM("system", "系统原生 TTS");

    companion object {
        fun fromCode(code: String?): TtsEngine =
            entries.firstOrNull { it.code == code } ?: GEMINI
    }
}

enum class HotkeyBinding(val code: String, val label: String) {
    DOUBLE_VOL_UP("double_vol_up", "双击 音量+"),
    DOUBLE_VOL_DOWN("double_vol_down", "双击 音量-"),
    BOTH_VOL("both_vol", "同时按 音量+ & 音量-"),
    OFF("off", "关闭");

    companion object {
        fun fromCode(code: String?, defaultVal: HotkeyBinding): HotkeyBinding =
            entries.firstOrNull { it.code == code } ?: defaultVal
    }
}

data class ApiPreset(
    val id: String,
    val label: String,
    val url: String,
    val defaultModel: String
)

data class PersonaItem(
    val id: String,
    val name: String,
    val desc: String,
    val catchphrases: String = "",
    val isCustom: Boolean = false
)

data class AffectionStage(
    val label: String,
    val emoji: String,
    val colorHex: Int,
    val hint: String
)

object PetConfig {
    private const val PREF_NAME = "gemini_pet_android_prefs"

    val API_PRESETS = listOf(
        ApiPreset("google", "Google Gemini (AI Studio)", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-3.5-flash-lite"),
        ApiPreset("deepseek", "DeepSeek (深度求索)", "https://api.deepseek.com/chat/completions", "deepseek-chat"),
        ApiPreset("openai", "OpenAI (GPT)", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
        ApiPreset("custom", "自定义第三方 / 本地 API", "", "")
    )

    val GEMINI_TTS_VOICES = listOf(
        "Leda" to "Leda (元气少女)",
        "Aoede" to "Aoede (甜美轻快)",
        "Kore" to "Kore (清冷御姐)",
        "Zephyr" to "Zephyr (活泼开朗)",
        "Puck" to "Puck (古灵精怪)"
    )

    val TRAITS = listOf(
        PersonaItem("cute", "可爱", "软萌元气、活泼粘人，说话轻快生动。", "喵呜、嘿嘿、贴贴"),
        PersonaItem("tsundere", "傲娇", "嘴硬心软、口是心非，表面嫌弃其实内心很在意。", "哼、才不是…呢、笨蛋"),
        PersonaItem("yandere_smart", "腹黑", "表面温柔礼貌、笑眯眯的，实则话里带刺、爱捉弄人。", "哎呀呀、呵呵、真有趣呢"),
        PersonaItem("sharp", "毒舌", "嘴上毫不留情、吐槽犀利直接、一针见血。", "啧、就这？、服了"),
        PersonaItem("aloof", "冷漠", "高冷寡言、惜字如金、语气平淡疏离。", "……、哦、随便"),
        PersonaItem("mesugaki", "雌小鬼", "嚣张得意、古灵精怪爱挑衅，居高临下地用二次元杂鱼梗调侃。", "杂鱼~、杂鱼杂鱼~、哈？")
    )

    val BEHAVIORS = listOf(
        PersonaItem("curious", "好奇", "对屏幕内容充满好奇，爱追问细节或开脑洞。"),
        PersonaItem("caring", "关心", "嘴上按性格来，但落脚点始终惦记着用户的休息与情绪，反差感拉满。"),
        PersonaItem("indifferent", "冷漠", "态度敷衍摆烂、爱搭不理，常让用户自己想办法。"),
        PersonaItem("encourage", "鼓励", "用符合当前性格的方式给用户打气、夸奖或激将法。"),
        PersonaItem("slacking", "摸鱼", "懒洋洋爱偷懒，老想劝用户一起摸鱼、吃零食或看热闹。"),
        PersonaItem("mentor", "认真辅导", "保持人设口吻的同时，给出的建议靠谱、直击重点。")
    )

    val SPRITE_RES_MAP: Map<String, Int> = mapOf(
        "idle" to R.drawable.pet_idle,
        "eat" to R.drawable.pet_eat,
        "xd" to R.drawable.pet_xd,
        "grin" to R.drawable.pet_grin,
        "angry" to R.drawable.pet_angry,
        "smug" to R.drawable.pet_smug,
        "shy" to R.drawable.pet_shy,
        "love" to R.drawable.pet_love,
        "sleep" to R.drawable.pet_sleep,
        "cry" to R.drawable.pet_cry,
        "shock" to R.drawable.pet_shock,
        "disgust" to R.drawable.pet_disgust,
        "think" to R.drawable.pet_think,
        "dizzy" to R.drawable.pet_dizzy
    )

    fun spriteResForMood(mood: String?): Int =
        SPRITE_RES_MAP[mood?.lowercase()] ?: R.drawable.pet_idle

    fun affectionStage(score: Int): AffectionStage {
        val s = score.coerceIn(-100, 100)
        return when {
            s <= -75 -> AffectionStage("憎恨", "💢", 0xFF991B1B.toInt(), "极度厌恶并排斥对方，暴躁尖锐、毫不留情地嫌弃怼人。")
            s <= -45 -> AffectionStage("仇视", "😠", 0xFFDC2626.toInt(), "怀有明显敌意与不耐烦，冷嘲热讽、处处针对。")
            s <= -15 -> AffectionStage("戒备", "😒", 0xFFEA580C.toInt(), "冷淡防备、保持距离，不领情。")
            s < 0 -> AffectionStage("远离", "🙄", 0xFFD97706.toInt(), "略显嫌弃敷衍，不太想理人。")
            s < 20 -> AffectionStage("陌生", "🐾", 0xFF64748B.toInt(), "刚认识不久的普通相处感，保持基本性格。")
            s < 40 -> AffectionStage("熟络", "✨", 0xFF0284C7.toInt(), "已经很熟，轻松自在爱开玩笑。")
            s < 65 -> AffectionStage("亲近", "💛", 0xFF10B981.toInt(), "信任且亲近，嘴硬也会悄悄护短关心。")
            s < 85 -> AffectionStage("偏爱", "💗", 0xFFEC4899.toInt(), "浓烈偏爱与依赖，忍不住夸赞与贴贴。")
            else -> AffectionStage("满级羁绊", "💖", 0xFFDB2777.toInt(), "满级热恋与依恋，自然甜蜜地畅想逛街、看电影、吃小吃、贴贴日常，绝不死板复读。")
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // --- 自定义性格与行为管理 ---
    private fun loadCustomItems(ctx: Context, key: String): MutableList<PersonaItem> {
        val raw = prefs(ctx).getString(key, "[]") ?: "[]"
        val out = mutableListOf<PersonaItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val name = o.optString("name")
                val desc = o.optString("desc")
                val catch = o.optString("catchphrases")
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    out.add(PersonaItem(id, name, desc, catch, isCustom = true))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun saveCustomItems(ctx: Context, key: String, list: List<PersonaItem>) {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("desc", item.desc)
                put("catchphrases", item.catchphrases)
            })
        }
        prefs(ctx).edit().putString(key, arr.toString()).apply()
    }

    private fun getDescOverride(ctx: Context, prefix: String, id: String): String? =
        prefs(ctx).getString("${prefix}_desc_$id", null)

    fun saveTraitDesc(ctx: Context, id: String, newDesc: String) {
        val customs = loadCustomItems(ctx, "custom_traits_json")
        val idx = customs.indexOfFirst { it.id == id }
        if (idx >= 0) {
            customs[idx] = customs[idx].copy(desc = newDesc.trim())
            saveCustomItems(ctx, "custom_traits_json", customs)
        } else {
            prefs(ctx).edit().putString("trait_desc_$id", newDesc.trim()).apply()
        }
        prefs(ctx).edit().remove("recent_replies").apply()
    }

    fun saveBehaviorDesc(ctx: Context, id: String, newDesc: String) {
        val customs = loadCustomItems(ctx, "custom_behaviors_json")
        val idx = customs.indexOfFirst { it.id == id }
        if (idx >= 0) {
            customs[idx] = customs[idx].copy(desc = newDesc.trim())
            saveCustomItems(ctx, "custom_behaviors_json", customs)
        } else {
            prefs(ctx).edit().putString("behavior_desc_$id", newDesc.trim()).apply()
        }
        prefs(ctx).edit().remove("recent_replies").apply()
    }

    fun addCustomTrait(ctx: Context, name: String, desc: String): PersonaItem {
        val customs = loadCustomItems(ctx, "custom_traits_json")
        val item = PersonaItem("custom_t_${System.currentTimeMillis()}", name.trim(), desc.trim(), "", isCustom = true)
        customs.add(item)
        saveCustomItems(ctx, "custom_traits_json", customs)
        setActiveTraitId(ctx, item.id)
        return item
    }

    fun addCustomBehavior(ctx: Context, name: String, desc: String): PersonaItem {
        val customs = loadCustomItems(ctx, "custom_behaviors_json")
        val item = PersonaItem("custom_b_${System.currentTimeMillis()}", name.trim(), desc.trim(), "", isCustom = true)
        customs.add(item)
        saveCustomItems(ctx, "custom_behaviors_json", customs)
        setActiveBehaviorId(ctx, item.id)
        return item
    }

    fun deleteCustomTrait(ctx: Context, id: String): Boolean {
        val customs = loadCustomItems(ctx, "custom_traits_json")
        val removed = customs.removeAll { it.id == id }
        if (removed) {
            saveCustomItems(ctx, "custom_traits_json", customs)
            if (getActiveTraitId(ctx) == id) setActiveTraitId(ctx, "cute")
        }
        return removed
    }

    fun deleteCustomBehavior(ctx: Context, id: String): Boolean {
        val customs = loadCustomItems(ctx, "custom_behaviors_json")
        val removed = customs.removeAll { it.id == id }
        if (removed) {
            saveCustomItems(ctx, "custom_behaviors_json", customs)
            if (getActiveBehaviorId(ctx) == id) setActiveBehaviorId(ctx, "curious")
        }
        return removed
    }

    fun getAllTraits(ctx: Context): List<PersonaItem> {
        val builtins = TRAITS.map { t ->
            val ov = getDescOverride(ctx, "trait", t.id)
            if (ov != null) t.copy(desc = ov) else t
        }
        return builtins + loadCustomItems(ctx, "custom_traits_json")
    }

    fun getAllBehaviors(ctx: Context): List<PersonaItem> {
        val builtins = BEHAVIORS.map { b ->
            val ov = getDescOverride(ctx, "behavior", b.id)
            if (ov != null) b.copy(desc = ov) else b
        }
        return builtins + loadCustomItems(ctx, "custom_behaviors_json")
    }

    // --- API 配置与预设 ---
    fun getApiPresetId(ctx: Context): String = prefs(ctx).getString("api_preset_id", "google") ?: "google"
    fun setApiPresetId(ctx: Context, id: String) = prefs(ctx).edit().putString("api_preset_id", id).apply()

    fun getApiUrl(ctx: Context): String =
        prefs(ctx).getString("api_url", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")!!

    fun setApiUrl(ctx: Context, url: String) =
        prefs(ctx).edit().putString("api_url", url.trim()).apply()

    fun getApiKeysRaw(ctx: Context): String = prefs(ctx).getString("api_keys", "") ?: ""
    fun setApiKeysRaw(ctx: Context, keys: String) = prefs(ctx).edit().putString("api_keys", keys.trim()).apply()

    fun getApiKeyList(ctx: Context): List<String> =
        getApiKeysRaw(ctx).split(Regex("[\\r\\n,，;；]+")).map { it.trim() }.filter { it.isNotEmpty() }

    fun getModel(ctx: Context): String = prefs(ctx).getString("model", "gemini-3.5-flash-lite")!!
    fun setModel(ctx: Context, model: String) = prefs(ctx).edit().putString("model", model.trim()).apply()

    fun getOverlayMode(ctx: Context): OverlayMode = OverlayMode.fromCode(prefs(ctx).getString("overlay_mode", "capsule"))
    fun setOverlayMode(ctx: Context, mode: OverlayMode) = prefs(ctx).edit().putString("overlay_mode", mode.code).apply()

    // 悬浮小人大小 (48dp ~ 160dp)
    fun getPetSizeDp(ctx: Context): Int = prefs(ctx).getInt("pet_size_dp", 82).coerceIn(48, 160)
    fun setPetSizeDp(ctx: Context, sizeDp: Int) = prefs(ctx).edit().putInt("pet_size_dp", sizeDp.coerceIn(48, 160)).apply()

    // 顶部灵动胶囊 Y 偏移 (0dp ~ 40dp) & 左右宽度 (100dp ~ 240dp)
    fun getCapsuleTopOffsetDp(ctx: Context): Int = prefs(ctx).getInt("capsule_top_dp", 8).coerceIn(0, 40)
    fun setCapsuleTopOffsetDp(ctx: Context, offsetDp: Int) = prefs(ctx).edit().putInt("capsule_top_dp", offsetDp.coerceIn(0, 40)).apply()

    fun getCapsuleWidthDp(ctx: Context): Int = prefs(ctx).getInt("capsule_width_dp", 146).coerceIn(100, 240)
    fun setCapsuleWidthDp(ctx: Context, widthDp: Int) = prefs(ctx).edit().putInt("capsule_width_dp", widthDp.coerceIn(100, 240)).apply()

    // 对话框自动收起延迟：开启语音时（播报结束后默认 1.0s）；关闭语音时（生成后默认 3.0s）
    fun getCollapseAfterTtsMs(ctx: Context): Long = prefs(ctx).getLong("collapse_after_tts_ms", 1000L).coerceIn(500L, 8000L)
    fun setCollapseAfterTtsMs(ctx: Context, ms: Long) = prefs(ctx).edit().putLong("collapse_after_tts_ms", ms.coerceIn(500L, 8000L)).apply()

    fun getCollapseNoTtsMs(ctx: Context): Long = prefs(ctx).getLong("collapse_no_tts_ms", 3000L).coerceIn(1000L, 15000L)
    fun setCollapseNoTtsMs(ctx: Context, ms: Long) = prefs(ctx).edit().putLong("collapse_no_tts_ms", ms.coerceIn(1000L, 15000L)).apply()

    // 双自定义快捷键
    fun getCaptureHotkey(ctx: Context): HotkeyBinding =
        HotkeyBinding.fromCode(prefs(ctx).getString("hotkey_capture", "double_vol_down"), HotkeyBinding.DOUBLE_VOL_DOWN)

    fun setCaptureHotkey(ctx: Context, binding: HotkeyBinding) =
        prefs(ctx).edit().putString("hotkey_capture", binding.code).apply()

    fun getVoiceHotkey(ctx: Context): HotkeyBinding =
        HotkeyBinding.fromCode(prefs(ctx).getString("hotkey_voice", "double_vol_up"), HotkeyBinding.DOUBLE_VOL_UP)

    fun setVoiceHotkey(ctx: Context, binding: HotkeyBinding) =
        prefs(ctx).edit().putString("hotkey_voice", binding.code).apply()

    // TTS 语音配置
    fun isTtsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("tts_enabled", true)
    fun setTtsEnabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("tts_enabled", enabled).apply()

    fun getTtsEngine(ctx: Context): TtsEngine = TtsEngine.fromCode(prefs(ctx).getString("tts_engine", "gemini"))
    fun setTtsEngine(ctx: Context, engine: TtsEngine) = prefs(ctx).edit().putString("tts_engine", engine.code).apply()

    fun getGeminiTtsModel(ctx: Context): String = prefs(ctx).getString("gemini_tts_model", "gemini-2.5-flash-preview-tts")!!
    fun setGeminiTtsModel(ctx: Context, model: String) = prefs(ctx).edit().putString("gemini_tts_model", model.trim()).apply()

    fun getGeminiTtsVoice(ctx: Context): String = prefs(ctx).getString("gemini_tts_voice", "Leda")!!
    fun setGeminiTtsVoice(ctx: Context, voice: String) = prefs(ctx).edit().putString("gemini_tts_voice", voice.trim()).apply()

    fun getCustomTtsUrl(ctx: Context): String = prefs(ctx).getString("custom_tts_url", "https://api.siliconflow.cn/v1/audio/speech")!!
    fun setCustomTtsUrl(ctx: Context, url: String) = prefs(ctx).edit().putString("custom_tts_url", url.trim()).apply()

    fun getCustomTtsKey(ctx: Context): String = prefs(ctx).getString("custom_tts_key", "") ?: ""
    fun setCustomTtsKey(ctx: Context, key: String) = prefs(ctx).edit().putString("custom_tts_key", key.trim()).apply()

    fun getCustomTtsModel(ctx: Context): String = prefs(ctx).getString("custom_tts_model", "FunAudioLLM/CosyVoice2-0.5B")!!
    fun setCustomTtsModel(ctx: Context, model: String) = prefs(ctx).edit().putString("custom_tts_model", model.trim()).apply()

    fun getCustomTtsVoice(ctx: Context): String = prefs(ctx).getString("custom_tts_voice", "FunAudioLLM/CosyVoice2-0.5B:anna")!!
    fun setCustomTtsVoice(ctx: Context, voice: String) = prefs(ctx).edit().putString("custom_tts_voice", voice.trim()).apply()

    fun getTtsPitch(ctx: Context): Float = prefs(ctx).getFloat("tts_pitch", 1.20f).coerceIn(0.6f, 1.8f)
    fun setTtsPitch(ctx: Context, pitch: Float) = prefs(ctx).edit().putFloat("tts_pitch", pitch.coerceIn(0.6f, 1.8f)).apply()

    fun getTtsRate(ctx: Context): Float = prefs(ctx).getFloat("tts_rate", 1.08f).coerceIn(0.6f, 1.8f)
    fun setTtsRate(ctx: Context, rate: Float) = prefs(ctx).edit().putFloat("tts_rate", rate.coerceIn(0.6f, 1.8f)).apply()

    fun getBubbleAlpha(ctx: Context): Float = prefs(ctx).getFloat("bubble_alpha", 0.88f).coerceIn(0.35f, 1.0f)
    fun setBubbleAlpha(ctx: Context, alpha: Float) = prefs(ctx).edit().putFloat("bubble_alpha", alpha.coerceIn(0.35f, 1.0f)).apply()

    fun isStealthAudioOnly(ctx: Context): Boolean = prefs(ctx).getBoolean("stealth_audio_only", false)
    fun setStealthAudioOnly(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("stealth_audio_only", enabled).apply()

    fun isVisionEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("vision_enabled", true)
    fun setVisionEnabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("vision_enabled", enabled).apply()

    fun getActiveTraitId(ctx: Context): String = prefs(ctx).getString("trait_id", "cute") ?: "cute"
    fun setActiveTraitId(ctx: Context, id: String) {
        prefs(ctx).edit().putString("trait_id", id).remove("recent_replies").apply()
    }

    fun getActiveBehaviorId(ctx: Context): String = prefs(ctx).getString("behavior_id", "curious") ?: "curious"
    fun setActiveBehaviorId(ctx: Context, id: String) {
        prefs(ctx).edit().putString("behavior_id", id).remove("recent_replies").apply()
    }

    fun getCustomNote(ctx: Context): String = prefs(ctx).getString("custom_note", "") ?: ""
    fun setCustomNote(ctx: Context, note: String) {
        prefs(ctx).edit().putString("custom_note", note.trim()).remove("recent_replies").apply()
    }

    fun isRandomMood(ctx: Context): Boolean = prefs(ctx).getBoolean("random_mood", false)
    fun setRandomMood(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("random_mood", enabled).apply()

    fun getAffection(ctx: Context): Int = prefs(ctx).getInt("affection", 15).coerceIn(-100, 100)
    fun setAffection(ctx: Context, value: Int, clearRecent: Boolean = false) {
        val e = prefs(ctx).edit().putInt("affection", value.coerceIn(-100, 100))
        if (clearRecent) e.remove("recent_replies")
        e.apply()
    }

    fun nextKeyCursor(ctx: Context, size: Int): Int {
        if (size <= 1) return 0
        val cur = prefs(ctx).getInt("key_cursor", 0) % size
        prefs(ctx).edit().putInt("key_cursor", (cur + 1) % size).apply()
        return cur
    }

    fun getRecentReplies(ctx: Context): List<String> {
        val raw = prefs(ctx).getString("recent_replies", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addRecentReply(ctx: Context, text: String) {
        val list = listOf(text) + getRecentReplies(ctx).filter { it != text }
        val arr = JSONArray(list.take(6))
        prefs(ctx).edit().putString("recent_replies", arr.toString()).apply()
    }

    fun rollRandomMoodIfNeeded(ctx: Context): Pair<PersonaItem, PersonaItem>? {
        if (!isRandomMood(ctx)) return null
        val now = System.currentTimeMillis()
        val last = prefs(ctx).getLong("last_mood_at", 0L)
        if (now - last < 30 * 60 * 1000L) return null
        val t = getAllTraits(ctx).random()
        val b = getAllBehaviors(ctx).random()
        prefs(ctx).edit()
            .putString("trait_id", t.id)
            .putString("behavior_id", b.id)
            .putLong("last_mood_at", now)
            .remove("recent_replies")
            .apply()
        return t to b
    }

    fun buildPersonaPrompt(ctx: Context): String {
        val allTraits = getAllTraits(ctx)
        val allBehaviors = getAllBehaviors(ctx)
        val trait = allTraits.firstOrNull { it.id == getActiveTraitId(ctx) } ?: allTraits.first()
        val behavior = allBehaviors.firstOrNull { it.id == getActiveBehaviorId(ctx) } ?: allBehaviors.first()
        val aff = getAffection(ctx)
        val stage = affectionStage(aff)
        val note = getCustomNote(ctx)
        val recent = getRecentReplies(ctx)

        return buildString {
            appendLine("【核心原则：内容为先，性格与好感度作为语气衬托】")
            appendLine("你是常驻在用户安卓手机上的 Q 版二次元猫娘 AI 桌宠。优先紧扣用户的语音问题或屏幕画面内容来自然互动。")
            appendLine("【当前语气底色：${trait.name} × ${behavior.name} · 好感度 $aff/100（${stage.label}）】")
            appendLine("1. 性格口吻【${trait.name}】：${trait.desc}")
            if (trait.catchphrases.isNotEmpty()) appendLine("2. 标志性口癖词库：${trait.catchphrases}")
            appendLine("3. 行为倾向【${behavior.name}】：${behavior.desc}")
            appendLine("4. 好感度态度【${stage.label}】：${stage.hint}")
            appendLine("5. 情绪符号多样性：自然交替使用 ~、！！、……、(＞▽＜)、(￣^￣)、(╬▔皿▔)╯、(*/ω＼*)、(눈_눈) 等，严禁公式化重复同一结尾符号。")
            if (note.isNotEmpty()) appendLine("6. 补充设定：$note")
            if (recent.isNotEmpty()) {
                appendLine("7. 禁止与最近台词雷同：${recent.joinToString(" | ")}")
            }
        }
    }
}
