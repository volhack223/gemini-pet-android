package com.geminipet.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 全局双快捷键监听服务：
 * - 语音对话快捷键（默认：快速双击 音量+）
 * - 截屏吐槽快捷键（默认：快速双击 音量-）
 * 均可在 App 内自由改键或关闭
 */
class PetHotkeyService : AccessibilityService() {

    private var volUpDown = false
    private var volDownDown = false
    private var comboTriggered = false
    private var lastVolUpTapAt = 0L
    private var lastVolDownTapAt = 0L
    private var lastTriggerAt = 0L

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

        // 1. 检测「同时按 音量+ & 音量-」
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

        // 2. 检测「快速双击 音量+」
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

        // 3. 检测「快速双击 音量-」
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
