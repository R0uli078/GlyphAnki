package com.alcyon.glyphanki.prefs

import android.content.Context
import android.content.SharedPreferences

class FieldPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("field_prefs", Context.MODE_PRIVATE)

    fun getFrontFields(): MutableList<String> {
        val s = prefs.getString(KEY_FRONT_LIST, null)
        if (!s.isNullOrEmpty()) return s.split(SEP).filter { it.isNotBlank() }.toMutableList()
        val set = prefs.getStringSet(KEY_FRONT, null)
        val list = when {
            !set.isNullOrEmpty() -> set.toMutableList()
            else -> DEFAULT_FRONT.toMutableList()
        }
        setFrontFields(list)
        return list
    }

    fun getBackFields(): MutableList<String> {
        val s = prefs.getString(KEY_BACK_LIST, null)
        if (!s.isNullOrEmpty()) return s.split(SEP).filter { it.isNotBlank() }.toMutableList()
        val set = prefs.getStringSet(KEY_BACK, null)
        val list = when {
            !set.isNullOrEmpty() -> set.toMutableList()
            else -> DEFAULT_BACK.toMutableList()
        }
        setBackFields(list)
        return list
    }

    fun setFrontFields(list: List<String>) {
        val cleaned = list.map { it.trim() }.filter { it.isNotBlank() }
        prefs.edit()
            .putString(KEY_FRONT_LIST, cleaned.joinToString(SEP))
            .putStringSet(KEY_FRONT, cleaned.toSet())
            .apply()
    }

    fun setBackFields(list: List<String>) {
        val cleaned = list.map { it.trim() }.filter { it.isNotBlank() }
        prefs.edit()
            .putString(KEY_BACK_LIST, cleaned.joinToString(SEP))
            .putStringSet(KEY_BACK, cleaned.toSet())
            .apply()
    }

    // Audio field lists
    fun getFrontAudioFields(): MutableList<String> {
        val s = prefs.getString(KEY_FRONT_AUDIO_LIST, null)
        if (!s.isNullOrEmpty()) return s.split(SEP).filter { it.isNotBlank() }.toMutableList()
        val list = DEFAULT_FRONT_AUDIO.toMutableList()
        setFrontAudioFields(list)
        return list
    }

    fun getBackAudioFields(): MutableList<String> {
        val s = prefs.getString(KEY_BACK_AUDIO_LIST, null)
        if (!s.isNullOrEmpty()) return s.split(SEP).filter { it.isNotBlank() }.toMutableList()
        val list = DEFAULT_BACK_AUDIO.toMutableList()
        setBackAudioFields(list)
        return list
    }

    fun setFrontAudioFields(list: List<String>) {
        val cleaned = list.map { it.trim() }.filter { it.isNotBlank() }
        prefs.edit()
            .putString(KEY_FRONT_AUDIO_LIST, cleaned.joinToString(SEP))
            .apply()
    }

    fun setBackAudioFields(list: List<String>) {
        val cleaned = list.map { it.trim() }.filter { it.isNotBlank() }
        prefs.edit()
            .putString(KEY_BACK_AUDIO_LIST, cleaned.joinToString(SEP))
            .apply()
    }

    fun ensureDefaults() {
        if (prefs.getString(KEY_FRONT_LIST, null).isNullOrEmpty()) setFrontFields(DEFAULT_FRONT)
        if (prefs.getString(KEY_BACK_LIST, null).isNullOrEmpty()) setBackFields(DEFAULT_BACK)
        if (prefs.getString(KEY_FRONT_AUDIO_LIST, null).isNullOrEmpty()) setFrontAudioFields(DEFAULT_FRONT_AUDIO)
        if (prefs.getString(KEY_BACK_AUDIO_LIST, null).isNullOrEmpty()) setBackAudioFields(DEFAULT_BACK_AUDIO)
    }

    companion object {
        private const val KEY_FRONT = "front_fields"
        private const val KEY_BACK = "back_fields"
        private const val KEY_FRONT_LIST = "front_fields_list"
        private const val KEY_BACK_LIST = "back_fields_list"
        private const val KEY_FRONT_AUDIO_LIST = "front_audio_fields_list"
        private const val KEY_BACK_AUDIO_LIST = "back_audio_fields_list"
        private const val SEP = "\u001F"
        // Requested text defaults
        val DEFAULT_FRONT = listOf("Word", "Expression")
        val DEFAULT_BACK = listOf("Glossary", "PrimaryDefinition", "Meaning")
        // Requested audio defaults
        val DEFAULT_FRONT_AUDIO = listOf("Audio", "WordAudio")
        val DEFAULT_BACK_AUDIO = listOf("Sentence-Audio", "SentenceAudio")
    }
}
