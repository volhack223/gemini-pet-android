package com.geminipet.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
            Toast.makeText(this, "截屏识图已就绪！点顶部灵动胶囊即可吐槽屏幕~", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未授予截屏权限，仍可使用纯文字与语音互动", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F8FAFC")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(36))
        }
        scroll.addView(root)

        fun sectionTitle(text: String) = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.parseColor("#202A5C"))
            setPadding(0, dp(14), 0, dp(6))
        }

        // 顶部头像与标题
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.pet_idle)
        }
        val titleTxt = TextView(this).apply {
            text = "🐾 Gemini 猫娘手机桌宠 v1.0\n灵动胶囊 · 一键截屏吐槽 · 语音播报"
            textSize = 15f
            setTextColor(Color.parseColor("#202A5C"))
            setPadding(dp(12), 0, 0, 0)
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(56), dp(56)))
        header.addView(titleTxt)
        root.addView(header)

        // 1. 权限与启动控制
        root.addView(sectionTitle("1. 启动悬浮窗与截屏识图"))
        val btnRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnOverlay = Button(this).apply {
            text = "授权悬浮窗"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this@MainActivity, "已获得悬浮窗权限 ✅", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val btnStart = Button(this).apply {
            text = "🚀 启动并授权截屏"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "请先点左边「授权悬浮窗」", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 100)
                }
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }
        val btnStop = Button(this).apply {
            text = "关闭"
            setOnClickListener {
                startService(Intent(this@MainActivity, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_STOP))
            }
        }
        btnRow1.addView(btnOverlay)
        btnRow1.addView(btnStart)
        btnRow1.addView(btnStop)
        root.addView(btnRow1)

        // 2. 显示形态选择
        root.addView(sectionTitle("2. 显示形态（长按悬浮窗也可随时切换）"))
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val curMode = PetConfig.getOverlayMode(this)
        OverlayMode.entries.forEach { mode ->
            val rb = RadioButton(this).apply {
                text = mode.label
                isChecked = mode == curMode
                setOnClickListener {
                    PetConfig.setOverlayMode(this@MainActivity, mode)
                    startService(Intent(this@MainActivity, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_REFRESH_UI))
                }
            }
            modeGroup.addView(rb)
        }
        root.addView(modeGroup)

        // 3. 语音播报与透明度
        root.addView(sectionTitle("3. 语音播报（TTS）与半透明气泡"))
        val cbTts = CheckBox(this).apply {
            text = "🔊 开启语音播报（不想看字直接听）"
            isChecked = PetConfig.isTtsEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> PetConfig.setTtsEnabled(this@MainActivity, checked) }
        }
        val cbVision = CheckBox(this).apply {
            text = "📸 开启内存级截屏识图（阅后即焚，零存储占用）"
            isChecked = PetConfig.isVisionEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> PetConfig.setVisionEnabled(this@MainActivity, checked) }
        }
        val alphaLabel = TextView(this).apply { text = "气泡不透明度：${(PetConfig.getBubbleAlpha(this@MainActivity) * 100).toInt()}%" }
        val alphaSeek = SeekBar(this).apply {
            max = 65
            progress = ((PetConfig.getBubbleAlpha(this@MainActivity) - 0.35f) * 100).toInt().coerceIn(0, 65)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val a = 0.35f + p / 100f
                    PetConfig.setBubbleAlpha(this@MainActivity, a)
                    alphaLabel.text = "气泡不透明度：${(a * 100).toInt()}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    startService(Intent(this@MainActivity, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_REFRESH_UI))
                }
            })
        }
        root.addView(cbTts)
        root.addView(cbVision)
        root.addView(alphaLabel)
        root.addView(alphaSeek)

        // 4. 好感度与投喂
        root.addView(sectionTitle("4. 双向好感度 (-100 ~ +100) & 投喂"))
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
                text = btnTxt
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

        // 5. 性格 × 行为选择
        root.addView(sectionTitle("5. 性格 × 行为人设组合"))
        val traitGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        PetConfig.TRAITS.forEach { t ->
            traitGroup.addView(RadioButton(this).apply {
                text = t.name
                textSize = 12f
                isChecked = t.id == PetConfig.getActiveTraitId(this@MainActivity)
                setOnClickListener { PetConfig.setActiveTraitId(this@MainActivity, t.id) }
            })
        }
        val behaviorGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        PetConfig.BEHAVIORS.forEach { b ->
            behaviorGroup.addView(RadioButton(this).apply {
                text = b.name
                textSize = 12f
                isChecked = b.id == PetConfig.getActiveBehaviorId(this@MainActivity)
                setOnClickListener { PetConfig.setActiveBehaviorId(this@MainActivity, b.id) }
            })
        }
        val cbRandomMood = CheckBox(this).apply {
            text = "🎲 开启随机心情（每 30 分钟自动换性格×行为）"
            isChecked = PetConfig.isRandomMood(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> PetConfig.setRandomMood(this@MainActivity, checked) }
        }
        root.addView(traitGroup)
        root.addView(behaviorGroup)
        root.addView(cbRandomMood)

        // 6. 大模型 API 配置
        root.addView(sectionTitle("6. 大模型 API 配置"))
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
            text = "保存配置"
            setOnClickListener {
                PetConfig.setApiUrl(this@MainActivity, etUrl.text.toString())
                PetConfig.setModel(this@MainActivity, etModel.text.toString())
                PetConfig.setApiKeysRaw(this@MainActivity, etKeys.text.toString())
                Toast.makeText(this@MainActivity, "配置已保存 ✅", Toast.LENGTH_SHORT).show()
            }
        })
        apiBtnRow.addView(Button(this).apply {
            text = "测试连接"
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

        // 7. 应用内快捷聊天
        root.addView(sectionTitle("7. 独立对话小窗"))
        val etChat = EditText(this).apply { hint = "直接在这里和猫娘聊天..." }
        val tvChatReply = TextView(this).apply { textSize = 13f; setPadding(0, dp(6), 0, dp(6)) }
        val btnSendChat = Button(this).apply {
            text = "发送对话"
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
        scope.cancel()
    }
}
