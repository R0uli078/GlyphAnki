package com.alcyon.glyphanki.session

object ReviewSessionState {
    @Volatile var active: Boolean = false
    @Volatile var currentDeckId: Long = -1L
    
    fun setActive(deckId: Long) {
        active = true
        currentDeckId = deckId
    }
    
    fun clear() {
        active = false
        currentDeckId = -1L
    }
}
