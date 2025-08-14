@file:Suppress("DirectSystemCurrentTimeMillisUsage")
package com.alcyon.glyphanki.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.KeyEvent
import android.content.Intent
import com.alcyon.glyphanki.service.ReviewForegroundService
import com.alcyon.glyphanki.session.ReviewSessionState
import android.util.Log

class VolumeAccessibilityService : AccessibilityService() {
    private var lastKeyTime = 0L
    private val debounceMs = 100L

    override fun onServiceConnected() {
        // Explicitly request key event filtering in addition to XML flag
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
        Log.d(TAG, "Accessibility service connected; key filtering requested")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        // Ignore auto-repeat events; only handle the first DOWN
        if (event.repeatCount > 0) return true
        val now = System.currentTimeMillis()
        if (now - lastKeyTime < debounceMs) return true
        lastKeyTime = now
        val active = ReviewSessionState.active
        Log.d(TAG, "Key down: code=${event.keyCode} sessionActive=$active")
        if (!active) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                val svc = Intent(this, ReviewForegroundService::class.java)
                svc.putExtra("action", "VOL_UP")
                runCatching { startService(svc) }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val svc = Intent(this, ReviewForegroundService::class.java)
                svc.putExtra("action", "VOL_DOWN")
                runCatching { startService(svc) }
                return true
            }
        }
        return false
    }

    override fun onInterrupt() { }

    companion object { private const val TAG = "VolumeAccessibility" }
}
