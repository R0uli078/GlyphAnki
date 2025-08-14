package com.alcyon.glyphanki.prefs

import android.content.Context
import android.net.Uri

class MediaPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("media_prefs", Context.MODE_PRIVATE)

    fun setMediaTreeUri(uri: Uri?) {
        val v = uri?.toString()
        prefs.edit().putString(KEY_TREE_URI, v).apply()
    }

    fun getMediaTreeUri(): Uri? {
        val s = prefs.getString(KEY_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }

    // New: audio enabled flag
    fun setAudioEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUDIO_ENABLED, enabled).apply()
    }

    fun isAudioEnabled(): Boolean = prefs.getBoolean(KEY_AUDIO_ENABLED, true)

    companion object {
        private const val KEY_TREE_URI = "media_tree_uri"
        private const val KEY_AUDIO_ENABLED = "audio_enabled"
    }
}
