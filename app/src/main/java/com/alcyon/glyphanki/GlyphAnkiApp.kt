package com.alcyon.glyphanki

import android.app.Application
import android.widget.Toast

class GlyphAnkiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Defensive init to catch any startup crash causes
        try {
            // Placeholder for future SDK inits (Glyph, etc.)
        } catch (e: Exception) {
            Toast.makeText(this, "App init error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}


