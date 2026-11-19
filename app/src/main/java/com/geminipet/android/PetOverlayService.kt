package com.geminipet.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.hypot

class PetOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.geminipet.android.ACTION_START"
        const val ACTION_STOP = "com.geminipet.android.ACTION_STOP"
        const val ACTION_REFRESH_UI = "com.geminipet.android.ACTION_REFRESH_UI"
        const val ACTION_TRIGGER_CAPTURE = "com.geminipet.android.ACTION_TRIGGER_CAPTURE"
        const val ACTION_TOGGLE_VOICE = "com.geminipet.android.ACTION_TOGGLE_VOICE"
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

    private var rootView: LinearLayout? = null
    private var topRowView: LinearLayout? = null
    private var textColView: LinearLayout? = null
    private var actionRow: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var avatarView: ImageView? = null
    private var titleView: TextView? = null
    private var speechTextView: TextView? = null
    private var pillDotsView: TextView? = null
    private var voiceBtnView: TextView? = null

    private var captureHelper: ScreenCaptureHelper? = null
    private var ttsManager: TtsManager? = null
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var pendingVoiceScreenshot: String? = null
    private var isRecording = false
    private var isSpeaking = false
    private var isBusy = false
    private var isCapsuleExpanded = false
    private var lastInteractAt = System.currentTimeMillis()
    private var currentMood = "idle"
    private var lastSpeechText = "双击音量+语音+看屏对话，双击音量-截屏吐槽~"

    // 记录悬浮小人展开气泡前的真实位置，方便右侧展开向左翻折后精准复原
    private var floatingAnchorX = -1
    private var floatingAnchorY = -1
    private var floatingExpandedOnLeft = false

    private val autoStopRecordRunnable = Runnable {
        if (isRecording) {
            stopVoiceAndSend()
        }
    }

    private val collapseRunnable = Runnable {
        if (!isRecording && !isSpeaking) {
            isCapsuleExpanded = false
            applyModeVisuals(expandedText = null, animate = true)
        }
    }

    private val idleSleepChecker = object : Runnable {
        override fun run() {
            if (!isBusy && !isRecording && !isSpeaking && System.currentTimeMillis() - lastInteractAt >= 90_000L && currentMood != "sleep") {
                currentMood = "sleep"
                avatarView?.setImageResource(PetConfig.spriteResForMood("sleep"))
            }
            mainHandler.postDelayed(this, 5000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ttsManager = TtsManager(this)
        createNotificationChannel()
        mainHandler.postDelayed(idleSleepChecker, 5000L)
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
            val hasMicPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (hasProjectionData) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            if (hasMicPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(NOTIF_ID, notif, fgsType)
            } catch (_: Exception) {
                startForeground(NOTIF_ID, notif)
            }
        } else {
            startForeground(NOTIF_ID, notif)
        }

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
            ACTION_REFRESH_UI -> applyModeVisuals(expandedText = if (isCapsuleExpanded) lastSpeechText else null, animate = false)
            ACTION_TRIGGER_CAPTURE -> triggerScreenReaction()
            ACTION_TOGGLE_VOICE -> toggleVoiceChat()
            ACTION_FEED -> {
                val itemName = intent?.getStringExtra(EXTRA_FEED_NAME) ?: "小鱼干"
                val delta = intent?.getIntExtra(EXTRA_FEED_DELTA, 6) ?: 6
                triggerFeedReaction(itemName, delta)
            }
            else -> applyModeVisuals(expandedText = null, animate = false)
        }
        return START_STICKY
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /**
     * 优先使用 Android 11+ 无障碍永不掉线截屏，未开无障碍时回退到持久化 MediaProjection
     */
    private suspend fun captureScreenBestEffort(): String? {
        if (!PetConfig.isVisionEnabled(this)) return null
        val accShot = PetHotkeyService.takeScreenshotDataUrl(48)
        if (!accShot.isNullOrBlank()) return accShot
        return captureHelper?.captureJpegDataUrl(48)
    }

    private fun pulseCapsule() {
        val root = rootView ?: return
        root.animate().cancel()
        root.scaleX = 0.92f
        root.scaleY = 0.92f
        root.animate()
            .scaleX(1.06f)
            .scaleY(1.06f)
            .setDuration(140L)
            .setInterpolator(OvershootInterpolator(1.2f))
            .withEndAction {
                root.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(140L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun makePillButton(label: String, bgColor: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = label
            this.textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(bgColor)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun ensureOverlayAttached() {
        if (rootView != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val rowTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val avatar = ImageView(this).apply {
            setImageResource(PetConfig.spriteResForMood("idle"))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
        }

        val titleTv = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11.5f
            visibility = View.GONE
        }

        val speechTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 5
            visibility = View.GONE
        }
        textCol.addView(titleTv)
        textCol.addView(speechTv)

        val dotsTv = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
            this.text = "······"
        }

        rowTop.addView(avatar, LinearLayout.LayoutParams(dp(24), dp(24)))
        rowTop.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rowTop.addView(dotsTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val rowActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(2))
            visibility = View.GONE
        }
        val btnVoice = makePillButton("🎙️ 语音", Color.parseColor("#0EA5E9")) {
            toggleVoiceChat()
        }
        val btnCapture = makePillButton("📸 截屏", Color.parseColor("#4F46E5")) {
            triggerScreenReaction()
        }
        val btnReplay = makePillButton("🔊 朗读", Color.parseColor("#2A2A35")) {
            speakIfEnabled(lastSpeechText)
        }
        val btnCollapse = makePillButton("收起 ▲", Color.parseColor("#2A2A35")) {
            mainHandler.removeCallbacks(collapseRunnable)
            if (isRecording) stopVoiceAndSend()
            isCapsuleExpanded = false
            applyModeVisuals(expandedText = null, animate = true)
        }
        fun btnLp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(6)
        }
        rowActions.addView(btnVoice, btnLp())
        rowActions.addView(btnCapture, btnLp())
        rowActions.addView(btnReplay, btnLp())
        rowActions.addView(btnCollapse)

        root.addView(rowTop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(rowActions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(PetConfig.getCapsuleTopOffsetDp(this@PetOverlayService))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressTriggered = false
        val longPressRunnable = Runnable {
            longPressTriggered = true
            val next = when (PetConfig.getOverlayMode(this)) {
                OverlayMode.CAPSULE -> OverlayMode.FLOATING
                OverlayMode.FLOATING -> OverlayMode.HIDDEN
                OverlayMode.HIDDEN -> OverlayMode.CAPSULE
            }
            PetConfig.setOverlayMode(this, next)
            isCapsuleExpanded = true
            applyModeVisuals(expandedText = "已切换形态：${next.label}", animate = true)
            mainHandler.removeCallbacks(collapseRunnable)
            mainHandler.postDelayed(collapseRunnable, 2400L)
        }

        root.setOnTouchListener { _, event ->
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
                            floatingAnchorX = lp.x
                            floatingAnchorY = lp.y
                            try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) {}
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressTriggered && event.action == MotionEvent.ACTION_UP) {
                        val mode = PetConfig.getOverlayMode(this)
                        if (mode == OverlayMode.CAPSULE) {
                            isCapsuleExpanded = !isCapsuleExpanded
                            mainHandler.removeCallbacks(collapseRunnable)
                            if (isCapsuleExpanded) {
                                applyModeVisuals(expandedText = lastSpeechText, animate = true)
                                mainHandler.postDelayed(collapseRunnable, 12000L)
                            } else {
                                applyModeVisuals(expandedText = null, animate = true)
                            }
                        } else {
                            triggerScreenReaction()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        rootView = root
        topRowView = rowTop
        textColView = textCol
        actionRow = rowActions
        avatarView = avatar
        titleView = titleTv
        speechTextView = speechTv
        pillDotsView = dotsTv
        voiceBtnView = btnVoice
        layoutParams = lp

        try {
            windowManager.addView(root, lp)
        } catch (_: Exception) {
            rootView = null
        }
    }

    private fun setTopRowOrder(bubbleOnLeft: Boolean) {
        val row = topRowView ?: return
        val avatar = avatarView ?: return
        val textCol = textColView ?: return
        val dots = pillDotsView ?: return

        val wantFirst = if (bubbleOnLeft) textCol else avatar
        if (row.getChildAt(0) === wantFirst) return

        row.removeAllViews()
        if (bubbleOnLeft) {
            row.addView(textCol, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(avatar)
            row.addView(dots)
        } else {
            row.addView(avatar)
            row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(dots)
        }
    }

    private fun applyModeVisuals(expandedText: String?, animate: Boolean = false) {
        val root = rootView ?: return
        val avatar = avatarView ?: return
        val title = titleView ?: return
        val speech = speechTextView ?: return
        val dots = pillDotsView ?: return
        val actions = actionRow ?: return
        val lp = layoutParams ?: return

        val mode = PetConfig.getOverlayMode(this)
        val alphaFloat = PetConfig.getBubbleAlpha(this)
        val bgAlpha = (alphaFloat * 255).toInt().coerceIn(90, 245)
        val aff = PetConfig.getAffection(this)
        val stage = PetConfig.affectionStage(aff)
        val isExpanded = !expandedText.isNullOrBlank()
        val dm = resources.displayMetrics

        voiceBtnView?.text = if (isRecording) "📤 说完了" else "🎙️ 语音"

        if (animate) {
            try {
                TransitionManager.beginDelayedTransition(root, AutoTransition().apply {
                    duration = 230L
                })
            } catch (_: Exception) {}
        }

        val bg = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE }

        when (mode) {
            OverlayMode.CAPSULE -> {
                setTopRowOrder(bubbleOnLeft = false)
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.x = 0
                lp.y = dp(PetConfig.getCapsuleTopOffsetDp(this))
                if (isExpanded) {
                    lp.width = (dm.widthPixels - dp(24)).coerceAtMost(dp(360))
                    lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                    bg.cornerRadius = dp(26).toFloat()
                    bg.setColor(Color.argb(bgAlpha.coerceAtLeast(228), 16, 17, 22))
                    bg.setStroke(dp(1), Color.argb(65, 255, 255, 255))
                    root.background = bg
                    root.setPadding(dp(16), dp(14), dp(16), dp(12))

                    avatar.visibility = View.VISIBLE
                    avatar.layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
                    dots.visibility = View.GONE
                    title.visibility = View.VISIBLE
                    title.text = "🐾 猫娘桌宠 · ${stage.emoji} ${stage.label} (${if (aff > 0) "+$aff" else "$aff"})"
                    speech.visibility = View.VISIBLE
                    speech.setTextColor(Color.WHITE)
                    speech.text = expandedText.orEmpty()
                    actions.visibility = View.VISIBLE
                } else {
                    lp.width = dp(146)
                    lp.height = dp(32)
                    bg.cornerRadius = dp(18).toFloat()
                    bg.setColor(Color.BLACK)
                    root.background = bg
                    root.setPadding(dp(7), dp(3), dp(9), dp(3))

                    avatar.visibility = View.VISIBLE
                    avatar.layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                    title.visibility = View.GONE
                    speech.visibility = View.GONE
                    dots.visibility = View.VISIBLE
                    dots.text = when {
                        isRecording -> "🎙️ 聆听中"
                        isBusy -> "● 思考中"
                        isSpeaking -> "🔊 播报中"
                        else -> "······"
                    }
                    actions.visibility = View.GONE
                }
            }
            OverlayMode.FLOATING -> {
                val petDp = PetConfig.getPetSizeDp(this)
                val petPx = dp(petDp)
                lp.gravity = Gravity.TOP or Gravity.START
                lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT

                if (floatingAnchorX < 0 || floatingAnchorY < 0) {
                    floatingAnchorX = dm.widthPixels - petPx - dp(16)
                    floatingAnchorY = (dm.heightPixels * 0.58f).toInt()
                }

                // 根据小人在屏幕左半边还是右半边，智能决定气泡向左还是向右展开
                val onRightHalf = (floatingAnchorX + petPx / 2) > (dm.widthPixels / 2)
                val bubbleMaxPx = dp(200)

                if (isExpanded) {
                    setTopRowOrder(bubbleOnLeft = onRightHalf)
                    floatingExpandedOnLeft = onRightHalf
                    lp.x = if (onRightHalf) {
                        (floatingAnchorX - bubbleMaxPx - dp(12)).coerceAtLeast(dp(8))
                    } else {
                        floatingAnchorX.coerceAtMost(dm.widthPixels - petPx - dp(12))
                    }
                    lp.y = floatingAnchorY
                } else {
                    setTopRowOrder(bubbleOnLeft = false)
                    floatingExpandedOnLeft = false
                    lp.x = floatingAnchorX
                    lp.y = floatingAnchorY
                }

                bg.cornerRadius = dp(18).toFloat()
                bg.setColor(if (isExpanded) Color.argb(bgAlpha, 20, 22, 32) else Color.TRANSPARENT)
                if (isExpanded) bg.setStroke(dp(1), Color.argb(140, 129, 140, 248))
                root.background = bg
                root.setPadding(dp(6), dp(6), dp(8), dp(6))

                avatar.visibility = View.VISIBLE
                avatar.layoutParams = LinearLayout.LayoutParams(petPx, petPx)
                dots.visibility = View.GONE
                title.visibility = View.GONE
                speech.visibility = if (isExpanded) View.VISIBLE else View.GONE
                speech.maxWidth = bubbleMaxPx
                speech.setTextColor(Color.WHITE)
                speech.text = expandedText.orEmpty()
                actions.visibility = View.GONE
            }
            OverlayMode.HIDDEN -> {
                setTopRowOrder(bubbleOnLeft = true)
                lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                lp.x = 0
                lp.y = 0
                bg.cornerRadius = dp(14).toFloat()
                bg.setColor(Color.argb(if (isExpanded) bgAlpha else 120, 20, 22, 32))
                root.background = bg
                root.setPadding(dp(6), dp(10), dp(6), dp(10))

                avatar.visibility = if (isExpanded) View.VISIBLE else View.GONE
                avatar.layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                dots.visibility = if (isExpanded) View.GONE else View.VISIBLE
                dots.text = when {
                    isRecording -> "🎙️"
                    isSpeaking -> "🔊"
                    else -> "🐾"
                }
                title.visibility = View.GONE
                speech.visibility = if (isExpanded) View.VISIBLE else View.GONE
                speech.maxWidth = dp(220)
                speech.setTextColor(Color.WHITE)
                speech.text = expandedText.orEmpty()
                actions.visibility = View.GONE
            }
        }

        avatar.setImageResource(PetConfig.spriteResForMood(currentMood))
        try {
            windowManager.updateViewLayout(root, lp)
            if (animate && mode == OverlayMode.CAPSULE) {
                root.animate().cancel()
                root.scaleX = if (isExpanded) 0.72f else 1.08f
                root.scaleY = if (isExpanded) 0.65f else 1.12f
                root.alpha = 0.85f
                root.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(240L)
                    .setInterpolator(if (isExpanded) OvershootInterpolator(0.75f) else DecelerateInterpolator())
                    .start()
            }
        } catch (_: Exception) {}
    }

    private fun presentReply(text: String, stealth: Boolean) {
        lastSpeechText = text
        if (stealth) {
            // 纯语音窥屏模式：不弹大卡片，仅顶部黑条轻跳 + 后台语音播报
            isCapsuleExpanded = false
            applyModeVisuals(expandedText = null, animate = false)
            pulseCapsule()
        } else {
            isCapsuleExpanded = true
            applyModeVisuals(expandedText = text, animate = true)
        }
        speakIfEnabled(text)
    }

    private fun toggleVoiceChat() {
        if (isRecording) {
            stopVoiceAndSend()
            return
        }
        if (isBusy) return
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            isCapsuleExpanded = true
            lastSpeechText = "⚠️ 请先在猫娘 App 首页点「授权麦克风」再使用语音对话~"
            applyModeVisuals(expandedText = lastSpeechText, animate = true)
            mainHandler.removeCallbacks(collapseRunnable)
            mainHandler.postDelayed(collapseRunnable, 5000L)
            return
        }

        ttsManager?.stopPlayer()
        isSpeaking = false
        lastInteractAt = System.currentTimeMillis()
        val stealth = PetConfig.isStealthAudioOnly(this)

        // 同步在开录瞬间截取当前屏幕，让语音对话也能看懂你正在看的画面！
        pendingVoiceScreenshot = null
        scope.launch {
            pendingVoiceScreenshot = captureScreenBestEffort()
        }

        try {
            val outFile = File(cacheDir, "pet_voice_input.m4a")
            if (outFile.exists()) outFile.delete()
            recordFile = outFile

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(16000)
            rec.setAudioEncodingBitRate(32000)
            rec.setOutputFile(outFile.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            isRecording = true
            currentMood = "think"
            mainHandler.removeCallbacks(collapseRunnable)
            if (stealth) {
                isCapsuleExpanded = false
                applyModeVisuals(expandedText = null, animate = false)
                pulseCapsule()
            } else {
                isCapsuleExpanded = true
                applyModeVisuals(expandedText = "🎙️ 正在听你说话（已同步看屏）… 说完再按一次快捷键发送", animate = true)
            }
            mainHandler.removeCallbacks(autoStopRecordRunnable)
            mainHandler.postDelayed(autoStopRecordRunnable, 9000L)
        } catch (e: Exception) {
            cleanupRecorder()
            isRecording = false
            isCapsuleExpanded = true
            applyModeVisuals(expandedText = "⚠️ 录音启动失败：${e.message}", animate = true)
            mainHandler.postDelayed(collapseRunnable, 4000L)
        }
    }

    private fun stopVoiceAndSend() {
        mainHandler.removeCallbacks(autoStopRecordRunnable)
        isRecording = false
        val stealth = PetConfig.isStealthAudioOnly(this)
        val file = recordFile
        try {
            recorder?.stop()
        } catch (_: Exception) {
        } finally {
            cleanupRecorder()
        }

        if (file == null || !file.exists() || file.length() < 500L) {
            if (!stealth) {
                isCapsuleExpanded = true
                applyModeVisuals(expandedText = "🎙️ 录音太短了，没听清呀~", animate = true)
                mainHandler.postDelayed(collapseRunnable, 3500L)
            } else {
                applyModeVisuals(expandedText = null, animate = false)
            }
            return
        }

        val audioB64 = try {
            val bytes = file.readBytes()
            file.delete()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
        if (audioB64.isNullOrEmpty()) return

        isBusy = true
        currentMood = "think"
        if (stealth) {
            applyModeVisuals(expandedText = null, animate = false)
            pulseCapsule()
        } else {
            isCapsuleExpanded = true
            applyModeVisuals(expandedText = "✨ 听到了！正在结合屏幕画面思考…", animate = false)
        }

        scope.launch {
            try {
                PetConfig.rollRandomMoodIfNeeded(this@PetOverlayService)
                val shot = pendingVoiceScreenshot ?: captureScreenBestEffort()
                pendingVoiceScreenshot = null
                val res = LlmClient.generateVoiceChat(this@PetOverlayService, audioB64, "audio/mp4", shot)
                if (res.ok) {
                    currentMood = res.mood
                    presentReply(res.text, stealth)
                } else {
                    currentMood = "dizzy"
                    lastSpeechText = "⚠️ ${res.error}"
                    isCapsuleExpanded = !stealth
                    applyModeVisuals(expandedText = if (stealth) null else lastSpeechText, animate = !stealth)
                    scheduleCollapseAfterDelay(5000L)
                }
            } finally {
                isBusy = false
                if (stealth && !isSpeaking) applyModeVisuals(expandedText = null, animate = false)
            }
        }
    }

    private fun cleanupRecorder() {
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    private fun triggerScreenReaction() {
        if (isBusy || isRecording) return
        isBusy = true
        lastInteractAt = System.currentTimeMillis()
        PetConfig.rollRandomMoodIfNeeded(this)
        val stealth = PetConfig.isStealthAudioOnly(this)

        currentMood = "think"
        mainHandler.removeCallbacks(collapseRunnable)
        if (stealth) {
            isCapsuleExpanded = false
            applyModeVisuals(expandedText = null, animate = false)
            pulseCapsule()
        } else {
            isCapsuleExpanded = true
            applyModeVisuals(expandedText = "喵呜？正在瞄一眼你的屏幕…", animate = true)
        }

        scope.launch {
            try {
                val screenshot = captureScreenBestEffort()
                val prompt = if (screenshot != null) {
                    "用户刚刚一键唤醒了你，想听你吐槽或点评此刻手机屏幕上的画面。"
                } else {
                    "用户刚刚唤醒了你（暂未获取到截屏画面），请用当前人设和好感度打个招呼或吐槽一句。"
                }
                val res = LlmClient.generateReaction(this@PetOverlayService, prompt, screenshot)
                if (res.ok) {
                    currentMood = res.mood
                    presentReply(res.text, stealth)
                } else {
                    currentMood = "dizzy"
                    lastSpeechText = "⚠️ ${res.error}"
                    isCapsuleExpanded = !stealth
                    applyModeVisuals(expandedText = if (stealth) null else lastSpeechText, animate = !stealth)
                    scheduleCollapseAfterDelay(5000L)
                }
            } finally {
                isBusy = false
                if (stealth && !isSpeaking) applyModeVisuals(expandedText = null, animate = false)
            }
        }
    }

    private fun triggerFeedReaction(itemName: String, delta: Int) {
        if (isBusy || isRecording) return
        isBusy = true
        lastInteractAt = System.currentTimeMillis()
        val stealth = PetConfig.isStealthAudioOnly(this)
        currentMood = if (delta >= 0) "eat" else "disgust"
        mainHandler.removeCallbacks(collapseRunnable)
        if (!stealth) {
            isCapsuleExpanded = true
            applyModeVisuals(expandedText = "投喂了「$itemName」（好感 ${if (delta >= 0) "+$delta" else "$delta"}）…", animate = true)
        } else {
            pulseCapsule()
        }

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
                    presentReply(res.text, stealth)
                } else {
                    presentReply(if (delta >= 0) "啊呜~ 吃掉$itemName 啦！" else "呸呸！才不要吃$itemName！", stealth)
                }
            } finally {
                isBusy = false
            }
        }
    }

    private fun scheduleCollapseAfterDelay(delayMs: Long) {
        mainHandler.removeCallbacks(collapseRunnable)
        mainHandler.postDelayed(collapseRunnable, delayMs)
    }

    private fun speakIfEnabled(rawText: String) {
        if (!PetConfig.isTtsEnabled(this)) {
            scheduleCollapseAfterDelay(10000L)
            return
        }
        isSpeaking = true
        mainHandler.removeCallbacks(collapseRunnable)
        if (PetConfig.isStealthAudioOnly(this)) {
            applyModeVisuals(expandedText = null, animate = false)
        }
        scope.launch {
            ttsManager?.speak(rawText) {
                isSpeaking = false
                if (PetConfig.isStealthAudioOnly(this@PetOverlayService)) {
                    applyModeVisuals(expandedText = null, animate = false)
                } else {
                    // 语音播报完整结束后，再留 3 秒方便回看文字再平滑收起
                    scheduleCollapseAfterDelay(3000L)
                }
            }
        }
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
        val voiceIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PetOverlayService::class.java).setAction(ACTION_TOGGLE_VOICE),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gemini 猫娘桌宠运行中")
            .setContentText("双击音量+语音看屏对话 · 双击音量-截屏吐槽")
            .setSmallIcon(R.drawable.pet_icon)
            .setContentIntent(openIntent)
            .addAction(0, "🎙️ 语音对话", voiceIntent)
            .addAction(0, "📸 截屏吐槽", captureIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        cleanupRecorder()
        scope.cancel()
        captureHelper?.release()
        ttsManager?.release()
        rootView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        rootView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
