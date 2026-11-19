package com.geminipet.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 全局硬件快捷键监听服务：
 * - 模式 1：同时按下「音量+」和「音量-」触发截屏吐槽
 * - 模式 2：400ms 内连按两下「音量-」触发截屏吐槽
 */
class PetHotkeyService : AccessibilityService() {

    private var volUpDown = false
    private var volDownDown = false
    private var comboTriggered = false
    private var lastVolDownTapAt = 0L
    private var lastTriggerAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不读取任何应用内容，仅监听按键事件
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val mode = PetConfig.getHotkeyMode(this)
        if (mode == HotkeyMode.OFF) return false

        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        val now = System.currentTimeMillis()
        when (mode) {
            HotkeyMode.BOTH_VOL -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (code == KeyEvent.KEYCODE_VOLUME_UP) volUpDown = true
                    if (code == KeyEvent.KEYCODE_VOLUME_DOWN) volDownDown = true
                    if (volUpDown && volDownDown && !comboTriggered) {
                        comboTriggered = true
                        fireTrigger(now)
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
            HotkeyMode.DOUBLE_VOL_DOWN -> {
                if (code == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (now - lastVolDownTapAt in 40L..420L) {
                        lastVolDownTapAt = 0L
                        fireTrigger(now)
                        return true
                    } else {
                        lastVolDownTapAt = now
                    }
                }
            }
            HotkeyMode.OFF -> return false
        }
        return false
    }

    private fun fireTrigger(now: Long) {
        if (now - lastTriggerAt < 1200L) return
        lastTriggerAt = now
        try {
            val intent = Intent(this, PetOverlayService::class.java).apply {
                action = PetOverlayService.ACTION_TRIGGER_CAPTURE
            }
            startForegroundService(intent)
        } catch (_: Exception) {
            try {
                startService(Intent(this, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_TRIGGER_CAPTURE))
            } catch (_: Exception) {}
        }
    }
}
