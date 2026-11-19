package com.geminipet.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var previewTts: TtsManager? = null
    private var hotkeyStatusLabel: TextView? = null

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svc = Intent(this, PetOverlayService::class.java).apply {
                action = PetOverlayService.ACTION_START
                putExtra(PetOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(PetOverlayService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(svc)
            Toast.makeText(this, "桌宠已启动！建议开启无障碍服务，截屏永不掉线~", Toast.LENGTH_SHORT).show()
        } else {
            val svc = Intent(this, PetOverlayService::class.java).apply {
                action = PetOverlayService.ACTION_START
            }
            startForegroundService(svc)
            Toast.makeText(this, "已启动悬浮窗（开启无障碍服务后即可免弹窗永久截屏）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun isHotkeyAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains("$packageName/${PetHotkeyService::class.java.name}") ||
            enabled.contains("$packageName/.PetHotkeyService")
    }

    override fun onResume() {
        super.onResume()
        hotkeyStatusLabel?.text = if (isHotkeyAccessibilityEnabled()) {
            "状态：✅ 已开启（支持全局音量快捷键 + 安卓 11+ 永不掉线静默截屏）"
        } else {
            "状态：⚠️ 未开启无障碍服务（开启后同时解锁全局快捷键与永久免掉线截屏）"
        }
    }

    private fun <T> buildRadioGroup(
        items: List<T>,
        selected: T,
        orientation: Int,
        labelOf: (T) -> String,
        textSizeSp: Float = 12.5f,
        onSelect: (T) -> Unit
    ): RadioGroup {
        val group = RadioGroup(this).apply { this.orientation = orientation }
        var checkedId = View.NO_ID
        items.forEach { item ->
            val rbId = View.generateViewId()
            val rb = RadioButton(this).apply {
                id = rbId
                this.text = labelOf(item)
                this.textSize = textSizeSp
                setOnClickListener { onSelect(item) }
            }
            group.addView(rb)
            if (item == selected) checkedId = rbId
        }
        if (checkedId != View.NO_ID) {
            group.check(checkedId)
        }
        return group
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewTts = TtsManager(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F8FAFC")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(40))
        }
        scroll.addView(root)

        fun sectionTitle(label: String) = TextView(this).apply {
            this.text = label
            textSize = 15f
            setTextColor(Color.parseColor("#202A5C"))
            setPadding(0, dp(16), 0, dp(6))
        }

        // 顶部头像与标题
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply { setImageResource(R.drawable.pet_idle) }
        val titleTxt = TextView(this).apply {
            this.text = "🐾 Gemini 猫娘手机桌宠 v1.3\n丝滑灵动胶囊 · 纯语音窥屏 · 永久截屏"
            textSize = 14.5f
            setTextColor(Color.parseColor("#202A5C"))
            setPadding(dp(12), 0, 0, 0)
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(56), dp(56)))
        header.addView(titleTxt)
        root.addView(header)

        // 1. 权限与启动控制
        root.addView(sectionTitle("1. 授权与启动"))
        val btnRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow1.addView(Button(this).apply {
            this.text = "授权悬浮窗"
            textSize = 12f
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this@MainActivity, "已获得悬浮窗权限 ✅", Toast.LENGTH_SHORT).show()
                }
            }
        })
        btnRow1.addView(Button(this).apply {
            this.text = "🎙️ 授权麦克风"
            textSize = 12f
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                } else {
                    Toast.makeText(this@MainActivity, "已获得麦克风权限 ✅", Toast.LENGTH_SHORT).show()
                }
            }
        })
        val btnRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow2.addView(Button(this).apply {
            this.text = "🚀 启动桌宠"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "请先点上方「授权悬浮窗」", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val perms = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= 33) perms.add("android.permission.POST_NOTIFICATIONS")
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.RECORD_AUDIO)
                }
                if (perms.isNotEmpty()) {
                    requestPermissions(perms.toTypedArray(), 100)
                }
                // 如果已开启无障碍服务（Android 11+），直接免弹窗永久截屏启动；否则请求 MediaProjection
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isHotkeyAccessibilityEnabled()) {
                    val svc = Intent(this@MainActivity, PetOverlayService::class.java).apply {
                        action = PetOverlayService.ACTION_START
                    }
                    startForegroundService(svc)
                    Toast.makeText(this@MainActivity, "已使用无障碍永久截屏模式启动 ✅", Toast.LENGTH_SHORT).show()
                } else {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
                }
            }
        })
        btnRow2.addView(Button(this).apply {
            this.text = "关闭悬浮"
            setOnClickListener {
                startService(Intent(this@MainActivity, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_STOP))
            }
        })
        root.addView(btnRow1)
        root.addView(btnRow2)

        // 2. 显示形态、小人大小、灵动胶囊位置调节
        root.addView(sectionTitle("2. 显示形态、窥屏模式与大小/位置调节"))
        val modeGroup = buildRadioGroup(
            items = OverlayMode.entries,
            selected = PetConfig.getOverlayMode(this),
            orientation = RadioGroup.HORIZONTAL,
            labelOf = { it.label }
        ) { mode ->
            PetConfig.setOverlayMode(this@MainActivity, mode)
            startService(Intent(this@MainActivity, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_REFRESH_UI))
        }
        root.addView(modeGroup)

        val cbStealth = CheckBox(this).apply {
            this.text = "🎧 纯语音窥屏模式（不弹大卡片，仅顶部胶囊轻跳+后台播报语音）"
            isChecked = PetConfig.isStealthAudioOnly(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                PetConfig.setStealthAudioOnly(this@MainActivity, checked)
            }
        }
        root.addView(cbStealth)

        val sizeLabel = TextView(this).apply { this.text = "悬浮小人大小：${PetConfig.getPetSizeDp(this@MainActivity)} dp" }
        val sizeSeek = SeekBar(this).apply {
            max = 112
            progress = (PetConfig.getPetSizeDp(this@MainActivity) - 48).coerceIn(0, 112)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = 48 + p
                    PetConfig.setPetSizeDp(this@MainActivity, v)
                    sizeLabel.text = "悬浮小人大小：$v dp"
                    if (fromUser) {
                        startService(Intent(this@MainActivity, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_REFRESH_UI))
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val offsetLabel = TextView(this).apply { this.text = "顶部灵动胶囊垂直微调：${PetConfig.getCapsuleTopOffsetDp(this@MainActivity)} dp（对齐挖孔用）" }
        val offsetSeek = SeekBar(this).apply {
            max = 40
            progress = PetConfig.getCapsuleTopOffsetDp(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    PetConfig.setCapsuleTopOffsetDp(this@MainActivity, p)
                    offsetLabel.text = "顶部灵动胶囊垂直微调：$p dp（对齐挖孔用）"
                    if (fromUser) {
                        startService(Intent(this@MainActivity, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_REFRESH_UI))
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val alphaLabel = TextView(this).apply { this.text = "卡片/气泡不透明度：${(PetConfig.getBubbleAlpha(this@MainActivity) * 100).toInt()}%" }
        val alphaSeek = SeekBar(this).apply {
            max = 65
            progress = ((PetConfig.getBubbleAlpha(this@MainActivity) - 0.35f) * 100).toInt().coerceIn(0, 65)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val a = 0.35f + p / 100f
                    PetConfig.setBubbleAlpha(this@MainActivity, a)
                    alphaLabel.text = "卡片/气泡不透明度：${(a * 100).toInt()}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    startService(Intent(this@MainActivity, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_REFRESH_UI))
                }
            })
        }
        val cbVision = CheckBox(this).apply {
            this.text = "📸 开启内存级截屏识图（阅后即焚，零存储占用）"
            isChecked = PetConfig.isVisionEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> PetConfig.setVisionEnabled(this@MainActivity, checked) }
        }
        root.addView(sizeLabel)
        root.addView(sizeSeek)
        root.addView(offsetLabel)
        root.addView(offsetSeek)
        root.addView(alphaLabel)
        root.addView(alphaSeek)
        root.addView(cbVision)

        // 3. 全局自定义双快捷键
        root.addView(sectionTitle("3. 自定义硬件快捷键 & 永久截屏授权"))
        root.addView(TextView(this).apply {
            this.text = "🎙️ 语音+看屏对话快捷键（按一下开录并截屏，再按一下发送）："
            textSize = 12.5f
            setTextColor(Color.parseColor("#0F172A"))
        })
        root.addView(buildRadioGroup(
            items = HotkeyBinding.entries,
            selected = PetConfig.getVoiceHotkey(this),
            orientation = RadioGroup.VERTICAL,
            labelOf = { it.label }
        ) { PetConfig.setVoiceHotkey(this@MainActivity, it) })

        root.addView(TextView(this).apply {
            this.text = "📸 纯截屏吐槽快捷键："
            textSize = 12.5f
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(buildRadioGroup(
            items = HotkeyBinding.entries,
            selected = PetConfig.getCaptureHotkey(this),
            orientation = RadioGroup.VERTICAL,
            labelOf = { it.label }
        ) { PetConfig.setCaptureHotkey(this@MainActivity, it) })

        val btnAcc = Button(this).apply {
            this.text = "⌨️ 去开启无障碍服务（快捷键 + 永不掉线截屏）"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        val tvHotkeyStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, dp(4), 0, dp(4))
        }
        hotkeyStatusLabel = tvHotkeyStatus
        root.addView(btnAcc)
        root.addView(tvHotkeyStatus)

        // 4. 多引擎语音播报
        root.addView(sectionTitle("4. 语音播报（多引擎 TTS）"))
        val cbTts = CheckBox(this).apply {
            this.text = "🔊 开启语音播报"
            isChecked = PetConfig.isTtsEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> PetConfig.setTtsEnabled(this@MainActivity, checked) }
        }
        root.addView(cbTts)

        val curEngine = PetConfig.getTtsEngine(this)

        val geminiTtsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val etGeminiTtsModel = EditText(this).apply {
            hint = "Gemini TTS 模型（默认 gemini-2.5-flash-preview-tts）"
            setText(PetConfig.getGeminiTtsModel(this@MainActivity))
        }
        val curVoicePair = PetConfig.GEMINI_TTS_VOICES.firstOrNull { it.first == PetConfig.getGeminiTtsVoice(this) }
            ?: PetConfig.GEMINI_TTS_VOICES.first()
        val geminiVoiceGroup = buildRadioGroup(
            items = PetConfig.GEMINI_TTS_VOICES,
            selected = curVoicePair,
            orientation = RadioGroup.VERTICAL,
            labelOf = { it.second }
        ) { PetConfig.setGeminiTtsVoice(this@MainActivity, it.first) }
        geminiTtsBox.addView(TextView(this).apply {
            this.text = "复用下方 Gemini API Key，零额外配置即可发声："
            textSize = 12f
            setTextColor(Color.parseColor("#475569"))
        })
        geminiTtsBox.addView(etGeminiTtsModel)
        geminiTtsBox.addView(geminiVoiceGroup)

        val customTtsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val etCustomTtsUrl = EditText(this).apply {
            hint = "TTS 接口（如 https://api.siliconflow.cn/v1/audio/speech）"
            setText(PetConfig.getCustomTtsUrl(this@MainActivity))
        }
        val etCustomTtsKey = EditText(this).apply {
            hint = "TTS API Key（本地服务可留空）"
            setText(PetConfig.getCustomTtsKey(this@MainActivity))
        }
        val etCustomTtsModel = EditText(this).apply {
            hint = "TTS 模型（如 FunAudioLLM/CosyVoice2-0.5B）"
            setText(PetConfig.getCustomTtsModel(this@MainActivity))
        }
        val etCustomTtsVoice = EditText(this).apply {
            hint = "音色 ID（如 FunAudioLLM/CosyVoice2-0.5B:anna）"
            setText(PetConfig.getCustomTtsVoice(this@MainActivity))
        }
        customTtsBox.addView(etCustomTtsUrl)
        customTtsBox.addView(etCustomTtsKey)
        customTtsBox.addView(etCustomTtsModel)
        customTtsBox.addView(etCustomTtsVoice)

        val sysTtsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pitchLabel = TextView(this).apply { this.text = "音调：${"%.2f".format(PetConfig.getTtsPitch(this@MainActivity))}" }
        val pitchSeek = SeekBar(this).apply {
            max = 120
            progress = ((PetConfig.getTtsPitch(this@MainActivity) - 0.6f) * 100).toInt().coerceIn(0, 120)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = 0.6f + p / 100f
                    PetConfig.setTtsPitch(this@MainActivity, v)
                    pitchLabel.text = "音调：${"%.2f".format(v)}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val rateLabel = TextView(this).apply { this.text = "语速：${"%.2f".format(PetConfig.getTtsRate(this@MainActivity))}" }
        val rateSeek = SeekBar(this).apply {
            max = 120
            progress = ((PetConfig.getTtsRate(this@MainActivity) - 0.6f) * 100).toInt().coerceIn(0, 120)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = 0.6f + p / 100f
                    PetConfig.setTtsRate(this@MainActivity, v)
                    rateLabel.text = "语速：${"%.2f".format(v)}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        sysTtsBox.addView(pitchLabel)
        sysTtsBox.addView(pitchSeek)
        sysTtsBox.addView(rateLabel)
        sysTtsBox.addView(rateSeek)

        fun updateTtsBoxes(eng: TtsEngine) {
            geminiTtsBox.visibility = if (eng == TtsEngine.GEMINI) View.VISIBLE else View.GONE
            customTtsBox.visibility = if (eng == TtsEngine.OPENAI_COMPAT) View.VISIBLE else View.GONE
            sysTtsBox.visibility = if (eng == TtsEngine.SYSTEM) View.VISIBLE else View.GONE
        }
        updateTtsBoxes(curEngine)

        val ttsEngineGroup = buildRadioGroup(
            items = TtsEngine.entries,
            selected = curEngine,
            orientation = RadioGroup.HORIZONTAL,
            labelOf = { it.label },
            textSizeSp = 12f
        ) { eng ->
            PetConfig.setTtsEngine(this@MainActivity, eng)
            updateTtsBoxes(eng)
        }

        val ttsTestStatus = TextView(this).apply { textSize = 12f; setPadding(0, dp(4), 0, dp(4)) }
        val ttsBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ttsBtnRow.addView(Button(this).apply {
            this.text = "保存语音设置"
            setOnClickListener {
                PetConfig.setGeminiTtsModel(this@MainActivity, etGeminiTtsModel.text.toString())
                PetConfig.setCustomTtsUrl(this@MainActivity, etCustomTtsUrl.text.toString())
                PetConfig.setCustomTtsKey(this@MainActivity, etCustomTtsKey.text.toString())
                PetConfig.setCustomTtsModel(this@MainActivity, etCustomTtsModel.text.toString())
                PetConfig.setCustomTtsVoice(this@MainActivity, etCustomTtsVoice.text.toString())
                Toast.makeText(this@MainActivity, "语音设置已保存 ✅", Toast.LENGTH_SHORT).show()
            }
        })
        ttsBtnRow.addView(Button(this).apply {
            this.text = "🔊 试听语音"
            setOnClickListener {
                PetConfig.setGeminiTtsModel(this@MainActivity, etGeminiTtsModel.text.toString())
                PetConfig.setCustomTtsUrl(this@MainActivity, etCustomTtsUrl.text.toString())
                PetConfig.setCustomTtsKey(this@MainActivity, etCustomTtsKey.text.toString())
                PetConfig.setCustomTtsModel(this@MainActivity, etCustomTtsModel.text.toString())
                PetConfig.setCustomTtsVoice(this@MainActivity, etCustomTtsVoice.text.toString())
                ttsTestStatus.text = "⏳ 正在合成语音..."
                scope.launch {
                    val err = previewTts?.speak("喵呜~ 主人，新声音听起来怎么样呀？")
                    ttsTestStatus.text = if (err == null) "✅ 播放中" else "⚠️ $err"
                }
            }
        })
        root.addView(ttsEngineGroup)
        root.addView(geminiTtsBox)
        root.addView(customTtsBox)
        root.addView(sysTtsBox)
        root.addView(ttsBtnRow)
        root.addView(ttsTestStatus)

        // 5. 好感度与投喂
        root.addView(sectionTitle("5. 双向好感度 (-100 ~ +100) & 投喂"))
        val affLabel = TextView(this)
        fun refreshAffLabel() {
            val a = PetConfig.getAffection(this)
            val st = PetConfig.affectionStage(a)
            affLabel.text = "当前好感度：${st.emoji} ${st.label} ($a)"
        }
        refreshAffLabel()
        val affSeek = SeekBar(this).apply {
            max = 200
            progress = PetConfig.getAffection(this@MainActivity) + 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        PetConfig.setAffection(this@MainActivity, p - 100, clearRecent = true)
                        refreshAffLabel()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    startService(Intent(this@MainActivity, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_REFRESH_UI))
                }
            })
        }
        val feedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple("🐟小鱼干", "小鱼干", 6),
            Triple("🍮布丁", "焦糖布丁", 10),
            Triple("🥦苦瓜汁", "苦瓜汁", -8),
            Triple("🥛酸奶", "主人的酸奶", 999)
        ).forEach { (btnTxt, itemName, rawDelta) ->
            feedRow.addView(Button(this).apply {
                this.text = btnTxt
                textSize = 11f
                setOnClickListener {
                    val cur = PetConfig.getAffection(this@MainActivity)
                    val delta = if (rawDelta == 999) (if (cur >= 50) 12 else -10) else rawDelta
                    PetConfig.setAffection(this@MainActivity, cur + delta)
                    affSeek.progress = PetConfig.getAffection(this@MainActivity) + 100
                    refreshAffLabel()
                    val fi = Intent(this@MainActivity, PetOverlayService::class.java).apply {
                        action = PetOverlayService.ACTION_FEED
                        putExtra(PetOverlayService.EXTRA_FEED_NAME, itemName)
                        putExtra(PetOverlayService.EXTRA_FEED_DELTA, delta)
                    }
                    startService(fi)
                }
            })
        }
        root.addView(affLabel)
        root.addView(affSeek)
        root.addView(feedRow)

        // 6. 性格 × 行为选择
        root.addView(sectionTitle("6. 性格 × 行为人设组合"))
        val curTrait = PetConfig.TRAITS.firstOrNull { it.id == PetConfig.getActiveTraitId(this) } ?: PetConfig.TRAITS.first()
        val curBehavior = PetConfig.BEHAVIORS.firstOrNull { it.id == PetConfig.getActiveBehaviorId(this) } ?: PetConfig.BEHAVIORS.first()
        root.addView(buildRadioGroup(
            items = PetConfig.TRAITS,
            selected = curTrait,
            orientation = RadioGroup.HORIZONTAL,
            labelOf = { it.name },
            textSizeSp = 12f
        ) { PetConfig.setActiveTraitId(this@MainActivity, it.id) })
        root.addView(buildRadioGroup(
            items = PetConfig.BEHAVIORS,
            selected = curBehavior,
            orientation = RadioGroup.HORIZONTAL,
            labelOf = { it.name },
            textSizeSp = 12f
        ) { PetConfig.setActiveBehaviorId(this@MainActivity, it.id) })

        val cbRandomMood = CheckBox(this).apply {
            this.text = "🎲 开启随机心情（每 30 分钟自动换性格×行为）"
            isChecked = PetConfig.isRandomMood(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> PetConfig.setRandomMood(this@MainActivity, checked) }
        }
        root.addView(cbRandomMood)

        // 7. 大模型 API 配置
        root.addView(sectionTitle("7. 大模型 API 配置"))
        val etUrl = EditText(this).apply {
            hint = "OpenAI 兼容接口地址"
            setText(PetConfig.getApiUrl(this@MainActivity))
        }
        val etModel = EditText(this).apply {
            hint = "模型名（推荐 gemini-3.5-flash-lite）"
            setText(PetConfig.getModel(this@MainActivity))
        }
        val etKeys = EditText(this).apply {
            hint = "API Keys（每行一个自动轮询，本地局域网模型可留空）"
            minLines = 2
            setText(PetConfig.getApiKeysRaw(this@MainActivity))
        }
        val testStatus = TextView(this).apply { textSize = 12f; setPadding(0, dp(4), 0, dp(4)) }
        val apiBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        apiBtnRow.addView(Button(this).apply {
            this.text = "保存配置"
            setOnClickListener {
                PetConfig.setApiUrl(this@MainActivity, etUrl.text.toString())
                PetConfig.setModel(this@MainActivity, etModel.text.toString())
                PetConfig.setApiKeysRaw(this@MainActivity, etKeys.text.toString())
                Toast.makeText(this@MainActivity, "配置已保存 ✅", Toast.LENGTH_SHORT).show()
            }
        })
        apiBtnRow.addView(Button(this).apply {
            this.text = "测试连接"
            setOnClickListener {
                PetConfig.setApiUrl(this@MainActivity, etUrl.text.toString())
                PetConfig.setModel(this@MainActivity, etModel.text.toString())
                PetConfig.setApiKeysRaw(this@MainActivity, etKeys.text.toString())
                testStatus.text = "⏳ 正在测试连接..."
                scope.launch {
                    val res = LlmClient.testConnection(this@MainActivity)
                    testStatus.text = if (res.ok) "✅ 成功 (${res.latencyMs}ms): ${res.text}" else "❌ 失败: ${res.error}"
                }
            }
        })
        root.addView(etUrl)
        root.addView(etModel)
        root.addView(etKeys)
        root.addView(apiBtnRow)
        root.addView(testStatus)

        // 8. 应用内快捷聊天
        root.addView(sectionTitle("8. 独立对话小窗"))
        val etChat = EditText(this).apply { hint = "直接在这里和猫娘聊天..." }
        val tvChatReply = TextView(this).apply { textSize = 13f; setPadding(0, dp(6), 0, dp(6)) }
        val btnSendChat = Button(this).apply {
            this.text = "发送对话"
            setOnClickListener {
                val msg = etChat.text.toString().trim()
                if (msg.isEmpty()) return@setOnClickListener
                etChat.setText("")
                tvChatReply.text = "⏳ 思考中..."
                scope.launch {
                    val res = LlmClient.chat(this@MainActivity, msg)
                    tvChatReply.text = if (res.ok) "🐾：${res.text}" else "⚠️ ${res.error}"
                }
            }
        }
        root.addView(etChat)
        root.addView(btnSendChat)
        root.addView(tvChatReply)

        setContentView(scroll)
    }

    override fun onDestroy() {
        super.onDestroy()
        previewTts?.release()
        scope.cancel()
    }
}
