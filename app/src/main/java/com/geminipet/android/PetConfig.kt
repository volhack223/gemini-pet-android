package com.geminipet.android

import android.content.Context
import org.json.JSONArray

enum class OverlayMode(val code: String, val label: String) {
    CAPSULE("capsule", "顶部灵动胶囊"),
    FLOATING("floating", "半透明悬浮小人"),
    HIDDEN("hidden", "贴边隐身把手");

    companion object {
        fun fromCode(code: String?): OverlayMode =
            entries.firstOrNull { it.code == code } ?: CAPSULE
    }
}

data class PersonaItem(
    val id: String,
    val name: String,
    val desc: String,
    val catchphrases: String = ""
)

data class AffectionStage(
    val label: String,
    val emoji: String,
    val colorHex: Int,
    val hint: String
)

object PetConfig {
    private const val PREF_NAME = "gemini_pet_android_prefs"

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

    fun isTtsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("tts_enabled", true)
    fun setTtsEnabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("tts_enabled", enabled).apply()

    fun getTtsPitch(ctx: Context): Float = prefs(ctx).getFloat("tts_pitch", 1.22f).coerceIn(0.6f, 1.8f)
    fun setTtsPitch(ctx: Context, pitch: Float) = prefs(ctx).edit().putFloat("tts_pitch", pitch.coerceIn(0.6f, 1.8f)).apply()

    fun getBubbleAlpha(ctx: Context): Float = prefs(ctx).getFloat("bubble_alpha", 0.78f).coerceIn(0.35f, 1.0f)
    fun setBubbleAlpha(ctx: Context, alpha: Float) = prefs(ctx).edit().putFloat("bubble_alpha", alpha.coerceIn(0.35f, 1.0f)).apply()

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
        val t = TRAITS.random()
        val b = BEHAVIORS.random()
        prefs(ctx).edit()
            .putString("trait_id", t.id)
            .putString("behavior_id", b.id)
            .putLong("last_mood_at", now)
            .remove("recent_replies")
            .apply()
        return t to b
    }

    fun buildPersonaPrompt(ctx: Context): String {
        val trait = TRAITS.firstOrNull { it.id == getActiveTraitId(ctx) } ?: TRAITS.first()
        val behavior = BEHAVIORS.firstOrNull { it.id == getActiveBehaviorId(ctx) } ?: BEHAVIORS.first()
        val aff = getAffection(ctx)
        val stage = affectionStage(aff)
        val note = getCustomNote(ctx)
        val recent = getRecentReplies(ctx)

        return buildString {
            appendLine("【核心原则：手机屏幕内容为先，性格与好感度作为语气衬托】")
            appendLine("你是常驻在用户安卓手机顶部的 Q 版二次元猫娘 AI 桌宠。如果附带了当前手机屏幕截图，必须优先紧扣画面中用户正在刷的 App、视频、图文或聊天内容来吐槽或互动。")
            appendLine("【当前语气底色：${trait.name} × ${behavior.name} · 好感度 $aff/100（${stage.label}）】")
            appendLine("1. 性格口吻【${trait.name}】：${trait.desc}")
            if (trait.catchphrases.isNotEmpty()) appendLine("2. 标志性口癖词库：${trait.catchphrases}")
            appendLine("3. 行为倾向【${behavior.name}】：${behavior.desc}")
            appendLine("4. 好感度态度【${stage.label}】：${stage.hint}")
            appendLine("5. 情绪符号多样性：自然交替使用 ~、！！、……、(＞▽＜)、(￣^￣)、(╬▔皿▔)╯、(*/ω＼*)、(눈_눈) 等，严禁公式化重复同一结尾符号。")
            if (note.isNotEmpty()) appendLine("6. 补充设定：$note")
            if (recent.isNotEmpty()) appendLine("7. 严禁复读最近刚说过的话：${recent.joinToString("、") { "「$it」" }}")
        }
    }
}
