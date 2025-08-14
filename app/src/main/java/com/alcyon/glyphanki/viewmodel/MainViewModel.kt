package com.alcyon.glyphanki.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.alcyon.glyphanki.service.ReviewForegroundService

class MainViewModel(app: Application) : AndroidViewModel(app) {
    
    /**
     * Start deck session using simplified AnkiDroid Companion approach
     * Only needs deck ID and name - AnkiDroidHelper handles field mapping internally
     */
    fun startDeckSession(
        context: Context,
        deckId: Long,
        deckName: String
    ) {
        val intent = Intent(context, ReviewForegroundService::class.java)
        intent.putExtra(ReviewForegroundService.EXTRA_DECK_ID, deckId)
        context.startForegroundService(intent)
    }
    
    /**
     * Legacy method for backwards compatibility
     */
    fun startDeckSession(
        context: Context,
        deckId: Long,
        frontFieldName: String,
        backFieldName: String
    ) {
        // Use simplified approach - field names are handled by AnkiDroidHelper
        startDeckSession(context, deckId, "Deck $deckId")
    }
}


