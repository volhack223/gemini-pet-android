package com.geminipet.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.hypot

class PetOverlayService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.geminipet.android.ACTION_START"
        const val ACTION_STOP = "com.geminipet.android.ACTION_STOP"
        const val ACTION_REFRESH_UI = "com.geminipet.android.ACTION_REFRESH_UI"
        const val ACTION_TRIGGER_CAPTURE = "com.geminipet.android.ACTION_TRIGGER_CAPTURE"
        const val ACTION_FEED = "com.geminipet.android.ACTION_FEED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_FEED_NAME = "feed_name"
        const val EXTRA_FEED_DELTA = "feed_delta"
        private const val CHANNEL_ID = "gemini_pet_overlay_channel"
        private const val NOTIF_ID = 2026
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var avatarView: ImageView? = null
    private var textView: TextView? = null
    private var badgeView: TextView? = null
    private var containerLayout: LinearLayout? = null

    private var captureHelper: ScreenCaptureHelper? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isBusy = false
    private var lastInteractAt = System.currentTimeMillis()
    private var currentMood = "idle"

    private val collapseRunnable = Runnable {
        applyModeVisuals(expandedText = null)
    }

    private val idleSleepChecker = object : Runnable {
        override fun run() {
            if (!isBusy && System.currentTimeMillis() - lastInteractAt >= 90_000L && currentMood != "sleep") {
                currentMood = "sleep"
                avatarView?.setImageResource(PetConfig.spriteResForMood("sleep"))
            }
            mainHandler.postDelayed(this, 5000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        createNotificationChannel()
        mainHandler.postDelayed(idleSleepChecker, 5000L)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale.CHINA)
            ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasProjectionData = intent?.hasExtra(EXTRA_RESULT_DATA) == true
            val fgsType = if (hasProjectionData) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            try {
                startForeground(NOTIF_ID, notif, fgsType)
            } catch (_: Exception) {
                startForeground(NOTIF_ID, notif)
            }
        } else {
            startForeground(NOTIF_ID, notif)
        }

        // 初始化 MediaProjection（如果用户刚刚授权）
        if (intent?.hasExtra(EXTRA_RESULT_DATA) == true) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
            @Suppress("DEPRECATION")
            val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
            if (resultData != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mpm.getMediaProjection(resultCode, resultData)
                if (projection != null) {
                    captureHelper?.release()
                    captureHelper = ScreenCaptureHelper(this, projection)
                }
            }
        }

        ensureOverlayAttached()

        when (action) {
            ACTION_REFRESH_UI -> applyModeVisuals(expandedText = null)
            ACTION_TRIGGER_CAPTURE -> triggerScreenReaction()
            ACTION_FEED -> {
                val itemName = intent?.getStringExtra(EXTRA_FEED_NAME) ?: "小鱼干"
                val delta = intent?.getIntExtra(EXTRA_FEED_DELTA, 6) ?: 6
                triggerFeedReaction(itemName, delta)
            }
            else -> applyModeVisuals(expandedText = null)
        }
        return START_STICKY
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun ensureOverlayAttached() {
        if (rootView != null) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(10), dp(5))
        }

        val avatar = ImageView(this).apply {
            setImageResource(PetConfig.spriteResForMood("idle"))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12.5f
            maxLines = 4
            setPadding(dp(8), 0, dp(4), 0)
            visibility = View.GONE
        }

        val badge = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10.5f
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }

        container.addView(avatar, LinearLayout.LayoutParams(dp(30), dp(30)))
        container.addView(badge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(8)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressTriggered = false
        val longPressRunnable = Runnable {
            longPressTriggered = true
            // 长按循环切换显示形态：灵动胶囊 -> 半透明悬浮 -> 贴边隐身
            val next = when (PetConfig.getOverlayMode(this)) {
                OverlayMode.CAPSULE -> OverlayMode.FLOATING
                OverlayMode.FLOATING -> OverlayMode.HIDDEN
                OverlayMode.HIDDEN -> OverlayMode.CAPSULE
            }
            PetConfig.setOverlayMode(this, next)
            applyModeVisuals(expandedText = "已切换为：${next.label}")
            mainHandler.removeCallbacks(collapseRunnable)
            mainHandler.postDelayed(collapseRunnable, 2200L)
        }

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    longPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, 650L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (hypot(dx.toDouble(), dy.toDouble()) > dp(8)) {
                        moved = true
                        mainHandler.removeCallbacks(longPressRunnable)
                        if (PetConfig.getOverlayMode(this) == OverlayMode.FLOATING) {
                            lp.x = startX + dx.toInt()
                            lp.y = startY + dy.toInt()
                            try { windowManager.updateViewLayout(container, lp) } catch (_: Exception) {}
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressTriggered && event.action == MotionEvent.ACTION_UP) {
                        triggerScreenReaction()
                    }
                    true
                }
                else -> false
            }
        }

        rootView = container
        containerLayout = container
        avatarView = avatar
        textView = text
        badgeView = badge
        layoutParams = lp

        try {
            windowManager.addView(container, lp)
        } catch (_: Exception) {
            rootView = null
        }
    }

    private fun applyModeVisuals(expandedText: String?) {
        val container = containerLayout ?: return
        val avatar = avatarView ?: return
        val text = textView ?: return
        val badge = badgeView ?: return
        val lp = layoutParams ?: return

        val mode = PetConfig.getOverlayMode(this)
        val alphaFloat = PetConfig.getBubbleAlpha(this)
        val bgAlpha = (alphaFloat * 255).toInt().coerceIn(80, 245)
        val aff = PetConfig.getAffection(this)
        val stage = PetConfig.affectionStage(aff)
        val isExpanded = !expandedText.isNullOrBlank()

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
        }

        when (mode) {
            OverlayMode.CAPSULE -> {
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.x = 0
                lp.y = dp(6)
                bg.cornerRadius = dp(24).toFloat()
                bg.setColor(Color.argb(bgAlpha, 18, 22, 42))
                bg.setStroke(dp(1), Color.argb(180, 129, 140, 248))
                container.background = bg
                container.orientation = LinearLayout.HORIZONTAL
                container.setPadding(dp(10), dp(5), dp(12), dp(5))

                avatar.visibility = View.VISIBLE
                avatar.layoutParams = LinearLayout.LayoutParams(
                    if (isExpanded) dp(42) else dp(28),
                    if (isExpanded) dp(42) else dp(28)
                )
                badge.visibility = if (isExpanded) View.GONE else View.VISIBLE
                badge.text = "${stage.emoji}${if (aff > 0) "+$aff" else "$aff"}"
                text.visibility = if (isExpanded) View.VISIBLE else View.GONE
                text.maxWidth = dp(245)
                text.text = expandedText.orEmpty()
                text.setTextColor(Color.WHITE)
            }
            OverlayMode.FLOATING -> {
                lp.gravity = Gravity.TOP or Gravity.START
                if (lp.x == 0 && lp.y < dp(40)) {
                    val dm = resources.displayMetrics
                    lp.x = dm.widthPixels - dp(110)
                    lp.y = (dm.heightPixels * 0.62f).toInt()
                }
                bg.cornerRadius = dp(18).toFloat()
                bg.setColor(if (isExpanded) Color.argb(bgAlpha, 255, 255, 255) else Color.TRANSPARENT)
                if (isExpanded) bg.setStroke(dp(2), Color.rgb(32, 42, 92))
                container.background = bg
                container.orientation = LinearLayout.HORIZONTAL
                container.setPadding(dp(8), dp(6), dp(8), dp(6))

                avatar.visibility = View.VISIBLE
                avatar.layoutParams = LinearLayout.LayoutParams(dp(74), dp(74))
                badge.visibility = View.GONE
                text.visibility = if (isExpanded) View.VISIBLE else View.GONE
                text.maxWidth = dp(200)
                text.text = expandedText.orEmpty()
                text.setTextColor(Color.rgb(32, 42, 92))
            }
            OverlayMode.HIDDEN -> {
                lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                lp.x = 0
                lp.y = 0
                bg.cornerRadius = dp(14).toFloat()
                bg.setColor(Color.argb(if (isExpanded) bgAlpha else 110, 32, 42, 92))
                container.background = bg
                container.orientation = LinearLayout.HORIZONTAL
                container.setPadding(dp(6), dp(10), dp(6), dp(10))

                avatar.visibility = if (isExpanded) View.VISIBLE else View.GONE
                avatar.layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                badge.visibility = if (isExpanded) View.GONE else View.VISIBLE
                badge.text = "🐾"
                text.visibility = if (isExpanded) View.VISIBLE else View.GONE
                text.maxWidth = dp(220)
                text.text = expandedText.orEmpty()
                text.setTextColor(Color.WHITE)
            }
        }

        avatar.setImageResource(PetConfig.spriteResForMood(currentMood))
        try {
            windowManager.updateViewLayout(container, lp)
        } catch (_: Exception) {}
    }

    private fun triggerScreenReaction() {
        if (isBusy) return
        isBusy = true
        lastInteractAt = System.currentTimeMillis()
        PetConfig.rollRandomMoodIfNeeded(this)

        currentMood = "think"
        applyModeVisuals(expandedText = "喵呜？正在看你的屏幕…")
        mainHandler.removeCallbacks(collapseRunnable)

        scope.launch {
            try {
                val screenshot = if (PetConfig.isVisionEnabled(this@PetOverlayService)) {
                    captureHelper?.captureJpegDataUrl(48)
                } else {
                    null
                }
                val prompt = if (screenshot != null) {
                    "用户刚刚一键唤醒了你，想听你吐槽或点评此刻手机屏幕上的画面。"
                } else {
                    "用户刚刚戳了你一下（未开启截屏权限），请用当前人设和好感度打个招呼或吐槽一句。"
                }
                val res = LlmClient.generateReaction(this@PetOverlayService, prompt, screenshot)
                if (res.ok) {
                    currentMood = res.mood
                    applyModeVisuals(expandedText = res.text)
                    speakIfEnabled(res.text)
                } else {
                    currentMood = "dizzy"
                    applyModeVisuals(expandedText = "⚠️ ${res.error}")
                }
            } finally {
                isBusy = false
                mainHandler.removeCallbacks(collapseRunnable)
                mainHandler.postDelayed(collapseRunnable, 8500L)
            }
        }
    }

    private fun triggerFeedReaction(itemName: String, delta: Int) {
        if (isBusy) return
        isBusy = true
        lastInteractAt = System.currentTimeMillis()
        currentMood = if (delta >= 0) "eat" else "disgust"
        applyModeVisuals(expandedText = "投喂了「$itemName」（好感 ${if (delta >= 0) "+$delta" else "$delta"}）…")
        mainHandler.removeCallbacks(collapseRunnable)

        scope.launch {
            try {
                val dir = if (delta >= 0) "很开心并接受（好感 +$delta）" else "很嫌弃并排斥（好感 $delta）"
                val res = LlmClient.generateReaction(
                    this@PetOverlayService,
                    "用户刚刚在手机上给你投喂了「$itemName」，你的反应是$dir，请给出对应台词。",
                    null
                )
                if (res.ok) {
                    currentMood = res.mood
                    applyModeVisuals(expandedText = res.text)
                    speakIfEnabled(res.text)
                } else {
                    applyModeVisuals(expandedText = if (delta >= 0) "啊呜~ 吃掉$itemName 啦！" else "呸呸！才不要吃$itemName！")
                }
            } finally {
                isBusy = false
                mainHandler.removeCallbacks(collapseRunnable)
                mainHandler.postDelayed(collapseRunnable, 8500L)
            }
        }
    }

    private fun speakIfEnabled(rawText: String) {
        if (!ttsReady || !PetConfig.isTtsEnabled(this)) return
        // 清理颜文字与特殊符号，只朗读自然语音文本
        val spoken = rawText
            .replace(Regex("\\([^)]*\\)|（[^）]*）|[♥♡~～★☆💢😠😒🙄🐾✨💛💗💖]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (spoken.isEmpty()) return
        tts?.setPitch(PetConfig.getTtsPitch(this))
        tts?.setSpeechRate(1.08f)
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "pet_${System.currentTimeMillis()}")
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gemini 猫娘悬浮桌宠",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val captureIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PetOverlayService::class.java).setAction(ACTION_TRIGGER_CAPTURE),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gemini 猫娘桌宠运行中")
            .setContentText("点击顶部灵动胶囊或下方按钮即可一键截屏吐槽")
            .setSmallIcon(R.drawable.pet_icon)
            .setContentIntent(openIntent)
            .addAction(0, "📸 一键截屏吐槽", captureIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        captureHelper?.release()
        tts?.stop()
        tts?.shutdown()
        rootView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        rootView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
