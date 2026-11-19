package com.geminipet.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * 全局双快捷键监听 + Android 11+ 永不掉线无障碍内存截屏服务
 */
class PetHotkeyService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PetHotkeyService? = null
            private set

        /**
         * 通过 Android 11+ 无障碍 API 静默内存截屏，永不因 MediaProjection 超时而掉线
         */
        suspend fun takeScreenshotDataUrl(quality: Int = 48): String? {
            val svc = instance ?: return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return suspendCancellableCoroutine { cont ->
                try {
                    svc.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        svc.mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                var resultUrl: String? = null
                                try {
                                    val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                                    hw?.recycle()
                                    if (soft != null) {
                                        val targetW = 720
                                        val scale = targetW.toFloat() / soft.width.coerceAtLeast(1)
                                        val targetH = (soft.height * scale).toInt().coerceAtLeast(1)
                                        val scaled = Bitmap.createScaledBitmap(soft, targetW, targetH, true)
                                        soft.recycle()
                                        val baos = ByteArrayOutputStream()
                                        scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                                        scaled.recycle()
                                        resultUrl = "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                                    }
                                } catch (_: Exception) {
                                    resultUrl = null
                                } finally {
                                    try { screenshot.hardwareBuffer.close() } catch (_: Exception) {}
                                }
                                if (cont.isActive) cont.resume(resultUrl)
                            }

                            override fun onFailure(errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private var volUpDown = false
    private var volDownDown = false
    private var comboTriggered = false
    private var lastVolUpTapAt = 0L
    private var lastVolDownTapAt = 0L
    private var lastTriggerAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        val captureKey = PetConfig.getCaptureHotkey(this)
        val voiceKey = PetConfig.getVoiceHotkey(this)
        if (captureKey == HotkeyBinding.OFF && voiceKey == HotkeyBinding.OFF) return false

        val now = System.currentTimeMillis()

        if (captureKey == HotkeyBinding.BOTH_VOL || voiceKey == HotkeyBinding.BOTH_VOL) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_VOLUME_UP) volUpDown = true
                if (code == KeyEvent.KEYCODE_VOLUME_DOWN) volDownDown = true
                if (volUpDown && volDownDown && !comboTriggered) {
                    comboTriggered = true
                    if (voiceKey == HotkeyBinding.BOTH_VOL) {
                        fireAction(PetOverlayService.ACTION_TOGGLE_VOICE, now)
                    } else {
                        fireAction(PetOverlayService.ACTION_TRIGGER_CAPTURE, now)
                    }
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (code == KeyEvent.KEYCODE_VOLUME_UP) volUpDown = false
                if (code == KeyEvent.KEYCODE_VOLUME_DOWN) volDownDown = false
                if (!volUpDown && !volDownDown) {
                    val consumed = comboTriggered
                    comboTriggered = false
                    if (consumed) return true
                }
            }
        }

        if (code == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (voiceKey == HotkeyBinding.DOUBLE_VOL_UP || captureKey == HotkeyBinding.DOUBLE_VOL_UP) {
                if (now - lastVolUpTapAt in 40L..420L) {
                    lastVolUpTapAt = 0L
                    val act = if (voiceKey == HotkeyBinding.DOUBLE_VOL_UP) {
                        PetOverlayService.ACTION_TOGGLE_VOICE
                    } else {
                        PetOverlayService.ACTION_TRIGGER_CAPTURE
                    }
                    fireAction(act, now)
                    return true
                } else {
                    lastVolUpTapAt = now
                }
            }
        }

        if (code == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (captureKey == HotkeyBinding.DOUBLE_VOL_DOWN || voiceKey == HotkeyBinding.DOUBLE_VOL_DOWN) {
                if (now - lastVolDownTapAt in 40L..420L) {
                    lastVolDownTapAt = 0L
                    val act = if (captureKey == HotkeyBinding.DOUBLE_VOL_DOWN) {
                        PetOverlayService.ACTION_TRIGGER_CAPTURE
                    } else {
                        PetOverlayService.ACTION_TOGGLE_VOICE
                    }
                    fireAction(act, now)
                    return true
                } else {
                    lastVolDownTapAt = now
                }
            }
        }

        return false
    }

    private fun fireAction(actionName: String, now: Long) {
        if (now - lastTriggerAt < 700L) return
        lastTriggerAt = now
        try {
            val intent = Intent(this, PetOverlayService::class.java).apply {
                action = actionName
            }
            startForegroundService(intent)
        } catch (_: Exception) {
            try {
                startService(Intent(this, PetOverlayService::class.java).setAction(actionName))
            } catch (_: Exception) {}
        }
    }
}
