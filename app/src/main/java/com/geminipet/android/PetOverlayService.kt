package com.geminipet.android

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
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
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
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

    // 主窗口（灵动胶囊 / 悬浮小人本体 / 贴边把手）
    private var windowRoot: FrameLayout? = null
    private var cardView: LinearLayout? = null
    private var actionRow: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var avatarView: ImageView? = null
    private var titleView: TextView? = null
    private var speechTextView: TextView? = null
    private var pillDotsView: TextView? = null
    private var voiceBtnView: TextView? = null

    // 独立悬浮气泡窗口（专供悬浮小人/贴边模式使用，绝对不挤动小人本体坐标！）
    private var bubbleRoot: FrameLayout? = null
    private var bubbleTextView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isBubbleShowing = false

    private var morphAnimator: ValueAnimator? = null
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

    // 悬浮小人固定锚点坐标（只随用户拖拽改变，绝不被对话框修改）
    private var floatingAnchorX = -1
    private var floatingAnchorY = -1

    private val autoStopRecordRunnable = Runnable {
        if (isRecording) stopVoiceAndSend()
    }

    private val collapseRunnable = Runnable {
        if (!isRecording && !isSpeaking) {
            isCapsuleExpanded = false
            hideFloatingBubble(animate = true)
            if (PetConfig.getOverlayMode(this) == OverlayMode.CAPSULE) {
                applyModeVisuals(expandedText = null, animate = true)
            }
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
            if (hasProjectionData) fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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
            ACTION_REFRESH_UI -> {
                if (PetConfig.getOverlayMode(this) == OverlayMode.CAPSULE) {
                    hideFloatingBubble(animate = false)
                }
                applyModeVisuals(expandedText = if (isCapsuleExpanded) lastSpeechText else null, animate = false)
            }
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

    private suspend fun captureScreenBestEffort(): String? {
        if (!PetConfig.isVisionEnabled(this)) return null
        val accShot = PetHotkeyService.takeScreenshotDataUrl(48)
        if (!accShot.isNullOrBlank()) return accShot
        return captureHelper?.captureJpegDataUrl(48)
    }

    /**
     * 明显且不被裁切的 Q 弹果冻回弹动效
     */
    private fun playElasticBounce() {
        val card = cardView ?: return
        card.animate().cancel()
        card.pivotX = card.width / 2f
        card.pivotY = card.height / 2f
        card.scaleX = 0.86f
        card.scaleY = 1.14f
        card.animate()
            .scaleX(1.10f)
            .scaleY(0.90f)
            .setDuration(120L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                card.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(240L)
                    .setInterpolator(OvershootInterpolator(2.8f))
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
        if (windowRoot != null) return

        // 外层透明留白，保证 Q 弹放大时绝对不会被窗口边界裁切
        val outer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
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
            setPadding(dp(8), 0, dp(6), 0)
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

        card.addView(rowTop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(rowActions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        outer.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ))

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
            y = (dp(PetConfig.getCapsuleTopOffsetDp(this@PetOverlayService)) - dp(8)).coerceAtLeast(0)
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
            hideFloatingBubble(animate = false)
            isCapsuleExpanded = (next == OverlayMode.CAPSULE)
            applyModeVisuals(expandedText = if (next == OverlayMode.CAPSULE) "已切换形态：${next.label}" else null, animate = true)
            if (next != OverlayMode.CAPSULE) {
                showFloatingBubble("已切换形态：${next.label}")
            }
            scheduleCollapseAfterDelay(2400L)
        }

        card.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    longPressTriggered = false
                    // 按下瞬间给一个 Q 弹压扁反馈！
                    card.animate().cancel()
                    card.animate().scaleX(0.91f).scaleY(1.07f).setDuration(90L).start()
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
                            try { windowManager.updateViewLayout(outer, lp) } catch (_: Exception) {}
                            if (isBubbleShowing) {
                                repositionFloatingBubble()
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    // 松手立刻 Q 弹回弹
                    card.animate().cancel()
                    card.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(240L)
                        .setInterpolator(OvershootInterpolator(2.8f))
                        .start()

                    if (!moved && !longPressTriggered && event.action == MotionEvent.ACTION_UP) {
                        val mode = PetConfig.getOverlayMode(this)
                        if (mode == OverlayMode.CAPSULE) {
                            playElasticBounce()
                            isCapsuleExpanded = !isCapsuleExpanded
                            mainHandler.removeCallbacks(collapseRunnable)
                            if (isCapsuleExpanded) {
                                // 先让 Q 弹动效起手 70ms 再丝滑展开，触感极佳
                                mainHandler.postDelayed({
                                    if (isCapsuleExpanded) {
                                        applyModeVisuals(expandedText = lastSpeechText, animate = true)
                                    }
                                }, 70L)
                                scheduleCollapseAfterDelay(10000L)
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

        windowRoot = outer
        cardView = card
        actionRow = rowActions
        avatarView = avatar
        titleView = titleTv
        speechTextView = speechTv
        pillDotsView = dotsTv
        voiceBtnView = btnVoice
        layoutParams = lp

        try {
            windowManager.addView(outer, lp)
        } catch (_: Exception) {
            windowRoot = null
        }

        // 同时准备独立的悬浮气泡窗口（默认隐藏，专供悬浮小人与贴边模式使用）
        ensureBubbleWindowAttached()
    }

    private fun ensureBubbleWindowAttached() {
        if (bubbleRoot != null) return
        val bRoot = FrameLayout(this).apply {
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false
        }
        val bTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 5
            maxWidth = dp(215)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        bRoot.addView(bTv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        val blp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
        try {
            windowManager.addView(bRoot, blp)
            bubbleRoot = bRoot
            bubbleTextView = bTv
            bubbleParams = blp
        } catch (_: Exception) {
            bubbleRoot = null
        }
    }

    /**
     * 在悬浮小人/贴边模式弹出独立气泡窗口：
     * 严格测量气泡实际宽高，小人在右半屏则气泡必在左侧，小人在左半屏则气泡必在右侧，小人本体 0 像素移动！
     */
    private fun showFloatingBubble(msg: String) {
        val bRoot = bubbleRoot ?: return
        val bTv = bubbleTextView ?: return
        val bgAlpha = (PetConfig.getBubbleAlpha(this) * 255).toInt().coerceIn(110, 245)
        bTv.text = msg
        bTv.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.argb(bgAlpha, 20, 22, 32))
            setStroke(dp(1), Color.argb(150, 129, 140, 248))
        }
        val wasHidden = !isBubbleShowing
        isBubbleShowing = true
        bRoot.visibility = View.VISIBLE
        repositionFloatingBubble()
        if (wasHidden) {
            bRoot.animate().cancel()
            bRoot.alpha = 0f
            bRoot.scaleX = 0.86f
            bRoot.scaleY = 0.86f
            bRoot.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200L)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
    }

    private fun repositionFloatingBubble() {
        val bRoot = bubbleRoot ?: return
        val bTv = bubbleTextView ?: return
        val blp = bubbleParams ?: return
        val dm = resources.displayMetrics
        val mode = PetConfig.getOverlayMode(this)

        val maxWSpec = View.MeasureSpec.makeMeasureSpec(dp(220), View.MeasureSpec.AT_MOST)
        val freeSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        bTv.measure(maxWSpec, freeSpec)
        val bw = bTv.measuredWidth.coerceAtLeast(dp(60))
        val bh = bTv.measuredHeight.coerceAtLeast(dp(36))

        if (mode == OverlayMode.HIDDEN) {
            blp.x = (dm.widthPixels - bw - dp(44)).coerceAtLeast(dp(8))
            blp.y = ((dm.heightPixels - bh) / 2).coerceIn(dp(40), dm.heightPixels - bh - dp(24))
        } else {
            val petPx = dp(PetConfig.getPetSizeDp(this))
            val padH = dp(12)
            val padV = dp(8)
            val petLeft = floatingAnchorX + padH
            val petTop = floatingAnchorY + padV
            val petCenterX = petLeft + petPx / 2
            val onRightHalf = petCenterX > (dm.widthPixels / 2)

            blp.x = if (onRightHalf) {
                (petLeft - bw - dp(8)).coerceAtLeast(dp(8))
            } else {
                (petLeft + petPx + dp(8)).coerceAtMost((dm.widthPixels - bw - dp(8)).coerceAtLeast(dp(8)))
            }
            blp.y = (petTop + (petPx - bh) / 2).coerceIn(dp(40), (dm.heightPixels - bh - dp(24)).coerceAtLeast(dp(40)))
        }
        try {
            windowManager.updateViewLayout(bRoot, blp)
        } catch (_: Exception) {}
    }

    private fun hideFloatingBubble(animate: Boolean) {
        val bRoot = bubbleRoot ?: return
        if (!isBubbleShowing) {
            bRoot.visibility = View.GONE
            return
        }
        isBubbleShowing = false
        if (!animate) {
            bRoot.animate().cancel()
            bRoot.visibility = View.GONE
            return
        }
        bRoot.animate().cancel()
        bRoot.animate()
            .alpha(0f)
            .scaleX(0.90f)
            .scaleY(0.90f)
            .setDuration(160L)
            .withEndAction {
                if (!isBubbleShowing) bRoot.visibility = View.GONE
            }
            .start()
    }

    /**
     * 丝滑灵动胶囊连续形变动画
     */
    private fun animateCapsuleMorph(expand: Boolean, expandedText: String?) {
        val outer = windowRoot ?: return
        val card = cardView ?: return
        val avatar = avatarView ?: return
        val title = titleView ?: return
        val speech = speechTextView ?: return
        val dots = pillDotsView ?: return
        val actions = actionRow ?: return
        val lp = layoutParams ?: return
        val dm = resources.displayMetrics

        morphAnimator?.cancel()

        val aff = PetConfig.getAffection(this)
        val stage = PetConfig.affectionStage(aff)
        val bgAlpha = (PetConfig.getBubbleAlpha(this) * 255).toInt().coerceIn(220, 248)

        val collapsedW = dp(PetConfig.getCapsuleWidthDp(this))
        val expandedW = (dm.widthPixels - dp(28)).coerceAtMost(dp(356))
        val collapsedAvatar = dp(24)
        val expandedAvatar = dp(48)
        val collapsedRadius = dp(18).toFloat()
        val expandedRadius = dp(26).toFloat()
        val collapsedColor = Color.BLACK
        val expandedColor = Color.argb(bgAlpha, 16, 17, 22)

        outer.setPadding(dp(12), dp(8), dp(12), dp(8))
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.x = 0
        lp.y = (dp(PetConfig.getCapsuleTopOffsetDp(this)) - dp(8)).coerceAtLeast(0)
        lp.width = (if (expand) expandedW else card.width.coerceAtLeast(collapsedW)) + dp(24)
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        try { windowManager.updateViewLayout(outer, lp) } catch (_: Exception) {}

        val startW = if (card.width > 0) card.width else (if (expand) collapsedW else expandedW)
        val targetW = if (expand) expandedW else collapsedW
        val startAv = if (avatar.layoutParams.width > 0) avatar.layoutParams.width else (if (expand) collapsedAvatar else expandedAvatar)
        val targetAv = if (expand) expandedAvatar else collapsedAvatar

        val bg = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE }
        card.background = bg
        avatar.setImageResource(PetConfig.spriteResForMood(currentMood))

        if (expand) {
            dots.visibility = View.GONE
            title.visibility = View.VISIBLE
            title.text = "🐾 猫娘桌宠 · ${stage.emoji} ${stage.label} (${if (aff > 0) "+$aff" else "$aff"})"
            speech.visibility = View.VISIBLE
            speech.setTextColor(Color.WHITE)
            speech.text = expandedText.orEmpty()
            actions.visibility = View.VISIBLE
            title.alpha = 0f
            speech.alpha = 0f
            actions.alpha = 0f
        } else {
            dots.text = when {
                isRecording -> "🎙️ 聆听中"
                isBusy -> "● 思考中"
                isSpeaking -> "🔊 播报中"
                else -> "······"
            }
        }

        val evaluator = ArgbEvaluator()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (expand) 300L else 240L
            interpolator = if (expand) OvershootInterpolator(0.45f) else DecelerateInterpolator(1.6f)
            addUpdateListener { va ->
                val rawT = va.animatedValue as Float
                val t = rawT.coerceIn(0f, 1f)
                val curW = (startW + (targetW - startW) * rawT).toInt().coerceIn(collapsedW, expandedW + dp(12))
                val curAv = (startAv + (targetAv - startAv) * t).toInt().coerceAtLeast(dp(20))
                val curRadius = if (expand) collapsedRadius + (expandedRadius - collapsedRadius) * t else expandedRadius + (collapsedRadius - expandedRadius) * t
                val curColor = evaluator.evaluate(t, if (expand) collapsedColor else expandedColor, if (expand) expandedColor else collapsedColor) as Int

                bg.cornerRadius = curRadius
                bg.setColor(curColor)
                if (expand && t > 0.3f) {
                    bg.setStroke(dp(1), Color.argb((65 * t).toInt(), 255, 255, 255))
                } else {
                    bg.setStroke(0, Color.TRANSPARENT)
                }

                val padH = if (expand) dp(7) + ((dp(16) - dp(7)) * t).toInt() else dp(16) - ((dp(16) - dp(7)) * t).toInt()
                val padV = if (expand) dp(3) + ((dp(12) - dp(3)) * t).toInt() else dp(12) - ((dp(12) - dp(3)) * t).toInt()
                card.setPadding(padH, padV, padH, padV)

                val clp = (card.layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(curW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                clp.width = curW
                clp.height = FrameLayout.LayoutParams.WRAP_CONTENT
                card.layoutParams = clp

                val avLp = avatar.layoutParams
                avLp.width = curAv
                avLp.height = curAv
                avatar.layoutParams = avLp

                if (expand) {
                    val contentAlpha = ((t - 0.25f) / 0.75f).coerceIn(0f, 1f)
                    title.alpha = contentAlpha
                    speech.alpha = contentAlpha
                    actions.alpha = contentAlpha
                } else {
                    val fadeOut = (1f - t * 2.2f).coerceIn(0f, 1f)
                    title.alpha = fadeOut
                    speech.alpha = fadeOut
                    actions.alpha = fadeOut
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!expand) {
                        title.visibility = View.GONE
                        speech.visibility = View.GONE
                        actions.visibility = View.GONE
                        dots.visibility = View.VISIBLE
                        dots.alpha = 1f
                        card.setPadding(dp(7), dp(3), dp(9), dp(3))
                        val clp = card.layoutParams as FrameLayout.LayoutParams
                        clp.width = collapsedW
                        clp.height = dp(32)
                        card.layoutParams = clp
                        lp.width = collapsedW + dp(24)
                        lp.height = dp(32) + dp(16)
                        try { windowManager.updateViewLayout(outer, lp) } catch (_: Exception) {}
                    }
                }
            })
        }
        morphAnimator = anim
        anim.start()
    }

    private fun applyModeVisuals(expandedText: String?, animate: Boolean = false) {
        val outer = windowRoot ?: return
        val card = cardView ?: return
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

        if (mode == OverlayMode.CAPSULE && animate) {
            hideFloatingBubble(animate = false)
            animateCapsuleMorph(expand = isExpanded, expandedText = expandedText)
            return
        }

        morphAnimator?.cancel()
        val bg = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE }

        when (mode) {
            OverlayMode.CAPSULE -> {
                hideFloatingBubble(animate = false)
                val collapsedW = dp(PetConfig.getCapsuleWidthDp(this))
                val expandedW = (dm.widthPixels - dp(28)).coerceAtMost(dp(356))
                outer.setPadding(dp(12), dp(8), dp(12), dp(8))
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.x = 0
                lp.y = (dp(PetConfig.getCapsuleTopOffsetDp(this)) - dp(8)).coerceAtLeast(0)
                if (isExpanded) {
                    lp.width = expandedW + dp(24)
                    lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                    bg.cornerRadius = dp(26).toFloat()
                    bg.setColor(Color.argb(bgAlpha.coerceAtLeast(228), 16, 17, 22))
                    bg.setStroke(dp(1), Color.argb(65, 255, 255, 255))
                    card.background = bg
                    card.setPadding(dp(16), dp(12), dp(16), dp(12))
                    card.layoutParams = FrameLayout.LayoutParams(expandedW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)

                    avatar.visibility = View.VISIBLE
                    avatar.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    dots.visibility = View.GONE
                    title.visibility = View.VISIBLE
                    title.alpha = 1f
                    title.text = "🐾 猫娘桌宠 · ${stage.emoji} ${stage.label} (${if (aff > 0) "+$aff" else "$aff"})"
                    speech.visibility = View.VISIBLE
                    speech.alpha = 1f
                    speech.setTextColor(Color.WHITE)
                    speech.text = expandedText.orEmpty()
                    actions.visibility = View.VISIBLE
                    actions.alpha = 1f
                } else {
                    lp.width = collapsedW + dp(24)
                    lp.height = dp(32) + dp(16)
                    bg.cornerRadius = dp(18).toFloat()
                    bg.setColor(Color.BLACK)
                    card.background = bg
                    card.setPadding(dp(7), dp(3), dp(9), dp(3))
                    card.layoutParams = FrameLayout.LayoutParams(collapsedW, dp(32), Gravity.TOP or Gravity.CENTER_HORIZONTAL)

                    avatar.visibility = View.VISIBLE
                    avatar.layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                    title.visibility = View.GONE
                    speech.visibility = View.GONE
                    dots.visibility = View.VISIBLE
                    dots.alpha = 1f
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
                // 悬浮小人本体窗口：只显示猫娘本体，坐标彻底锁定在 floatingAnchorX/Y，气泡由独立窗口展示！
                val petDp = PetConfig.getPetSizeDp(this)
                val petPx = dp(petDp)
                outer.setPadding(dp(12), dp(8), dp(12), dp(8))
                lp.gravity = Gravity.TOP or Gravity.START
                lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                card.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)

                if (floatingAnchorX < 0 || floatingAnchorY < 0) {
                    floatingAnchorX = dm.widthPixels - petPx - dp(28)
                    floatingAnchorY = (dm.heightPixels * 0.58f).toInt()
                }
                lp.x = floatingAnchorX
                lp.y = floatingAnchorY

                bg.setColor(Color.TRANSPARENT)
                card.background = bg
                card.setPadding(0, 0, 0, 0)

                avatar.visibility = View.VISIBLE
                avatar.layoutParams = LinearLayout.LayoutParams(petPx, petPx)
                dots.visibility = View.GONE
                title.visibility = View.GONE
                speech.visibility = View.GONE
                actions.visibility = View.GONE

                if (isExpanded) {
                    showFloatingBubble(expandedText.orEmpty())
                } else {
                    hideFloatingBubble(animate = animate)
                }
            }
            OverlayMode.HIDDEN -> {
                outer.setPadding(dp(4), dp(4), dp(4), dp(4))
                lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                lp.x = 0
                lp.y = 0
                card.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                bg.cornerRadius = dp(14).toFloat()
                bg.setColor(Color.argb(120, 20, 22, 32))
                card.background = bg
                card.setPadding(dp(6), dp(10), dp(6), dp(10))

                avatar.visibility = View.GONE
                dots.visibility = View.VISIBLE
                dots.text = when {
                    isRecording -> "🎙️"
                    isSpeaking -> "🔊"
                    else -> "🐾"
                }
                title.visibility = View.GONE
                speech.visibility = View.GONE
                actions.visibility = View.GONE

                if (isExpanded) {
                    showFloatingBubble(expandedText.orEmpty())
                } else {
                    hideFloatingBubble(animate = animate)
                }
            }
        }

        avatar.setImageResource(PetConfig.spriteResForMood(currentMood))
        try {
            windowManager.updateViewLayout(outer, lp)
        } catch (_: Exception) {}
    }

    private fun showStatusOrBubble(msg: String, stealth: Boolean, animateCapsule: Boolean) {
        val mode = PetConfig.getOverlayMode(this)
        if (stealth) {
            isCapsuleExpanded = false
            hideFloatingBubble(animate = false)
            applyModeVisuals(expandedText = null, animate = false)
            playElasticBounce()
            return
        }
        if (mode == OverlayMode.CAPSULE) {
            val alreadyExpanded = isCapsuleExpanded
            isCapsuleExpanded = true
            applyModeVisuals(expandedText = msg, animate = animateCapsule && !alreadyExpanded)
        } else {
            // 悬浮小人/贴边模式：只更新表情与独立气泡，小人本体 0 位移！
            avatarView?.setImageResource(PetConfig.spriteResForMood(currentMood))
            showFloatingBubble(msg)
        }
    }

    private fun presentReply(text: String, stealth: Boolean) {
        lastSpeechText = text
        showStatusOrBubble(text, stealth, animateCapsule = true)
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
            lastSpeechText = "⚠️ 请先在猫娘 App 首页点「授权麦克风」再使用语音对话~"
            showStatusOrBubble(lastSpeechText, stealth = false, animateCapsule = true)
            scheduleCollapseAfterDelay(4000L)
            return
        }

        ttsManager?.stopPlayer()
        isSpeaking = false
        lastInteractAt = System.currentTimeMillis()
        val stealth = PetConfig.isStealthAudioOnly(this)

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
            showStatusOrBubble("🎙️ 正在听你说话（已同步看屏）… 再按一次发送", stealth, animateCapsule = true)
            mainHandler.removeCallbacks(autoStopRecordRunnable)
            mainHandler.postDelayed(autoStopRecordRunnable, 9000L)
        } catch (e: Exception) {
            cleanupRecorder()
            isRecording = false
            showStatusOrBubble("⚠️ 录音启动失败：${e.message}", stealth = false, animateCapsule = true)
            scheduleCollapseAfterDelay(4000L)
        }
    }

    private fun stopVoiceAndSend() {
        mainHandler.removeCallbacks(autoStopRecordRunnable)
        isRecording = false
        val stealth = PetConfig.isStealthAudioOnly(this)
        val file = recordFile
        try { recorder?.stop() } catch (_: Exception) {} finally { cleanupRecorder() }

        if (file == null || !file.exists() || file.length() < 500L) {
            if (!stealth) {
                showStatusOrBubble("🎙️ 录音太短了，没听清呀~", stealth = false, animateCapsule = false)
                scheduleCollapseAfterDelay(2500L)
            } else {
                applyModeVisuals(expandedText = null, animate = false)
            }
            return
        }

        val audioB64 = try {
            val bytes = file.readBytes()
            file.delete()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) { null }
        if (audioB64.isNullOrEmpty()) return

        isBusy = true
        currentMood = "think"
        showStatusOrBubble("✨ 听到了！正在结合屏幕画面思考…", stealth, animateCapsule = false)

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
                    showStatusOrBubble(lastSpeechText, stealth, animateCapsule = false)
                    scheduleCollapseAfterDelay(4000L)
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
        showStatusOrBubble("喵呜？正在瞄一眼你的屏幕…", stealth, animateCapsule = true)

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
                    showStatusOrBubble(lastSpeechText, stealth, animateCapsule = false)
                    scheduleCollapseAfterDelay(4000L)
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
        showStatusOrBubble("投喂了「$itemName」（好感 ${if (delta >= 0) "+$delta" else "$delta"}）…", stealth, animateCapsule = true)

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
            scheduleCollapseAfterDelay(PetConfig.getCollapseNoTtsMs(this))
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
                    scheduleCollapseAfterDelay(PetConfig.getCollapseAfterTtsMs(this@PetOverlayService))
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
        morphAnimator?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        cleanupRecorder()
        scope.cancel()
        captureHelper?.release()
        ttsManager?.release()
        windowRoot?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        bubbleRoot?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        windowRoot = null
        bubbleRoot = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
