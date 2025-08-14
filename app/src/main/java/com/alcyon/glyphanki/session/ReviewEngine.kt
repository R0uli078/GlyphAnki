@file:Suppress("DirectSystemCurrentTimeMillisUsage")
package com.alcyon.glyphanki.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.alcyon.glyphanki.anki.AnkiCard
import com.alcyon.glyphanki.glyph.GlyphController
import com.alcyon.glyphanki.audio.AudioPlayer
import com.alcyon.glyphanki.parser.HtmlParser
import kotlinx.coroutines.*
import com.alcyon.glyphanki.prefs.MediaPreferences

class ReviewEngine(private val context: Context) {
    
    companion object {
        private const val TAG = "ReviewEngine"
    }
    
    enum class Phase { FRONT, BACK, TRANSITION }
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val glyphController = GlyphController(context)
    private val audio = AudioPlayer(context)
    
    private var currentCard: AnkiCard? = null
    private var phase = Phase.TRANSITION
    private var cardStartTime = 0L
    private var backShownMs: Long = 0L
    private var lastKeyTime: Long = 0L
    private val keyDebounceMs = 60L
    private var lastGradedId: Long = -1L
    private val minBackDwellMs = 150L
    
    private var cardLoader: (suspend () -> AnkiCard?)? = null
    private var answerFunction: (suspend (AnkiCard, Int, Long) -> Boolean)? = null
    private var enableBackAutoAdvance = false
    
    fun startSession(
        loader: suspend () -> AnkiCard?,
        answerer: suspend (AnkiCard, Int, Long) -> Boolean,
        autoAdvanceBack: Boolean = false
    ) {
        cardLoader = loader
        answerFunction = answerer
        enableBackAutoAdvance = autoAdvanceBack
        
        scope.launch {
            loadNextCard()
        }
    }
    
    fun stopSession() {
        Log.d(TAG, "Stopping review session")
        scope.cancel()
        glyphController.release()
        audio.release()
        ReviewSessionState.clear()
    }
    
    // Expose glyph controller lifecycle helpers so callers reuse the same instance
    fun warmUpGlyph() { glyphController.showHoldPixel(80) }
    fun startGlyphKeepAlive(periodMs: Long = 2500L) { glyphController.startKeepAlive(periodMs) }
    fun stopGlyphKeepAlive() { glyphController.stopKeepAlive() }
    suspend fun waitGlyphReady(timeoutMs: Long = 2000L): Boolean = glyphController.waitUntilReady(timeoutMs)
    
    fun gradeAndAdvance(ease: Int) {
        Log.d(TAG, "gradeAndAdvance called with ease=$ease")
        val card = currentCard ?: run {
            Log.w(TAG, "gradeAndAdvance: no current card")
            return
        }
        val cardId = (card.noteId shl 8) or card.cardOrd.toLong()
        Log.d(TAG, "gradeAndAdvance: currentCardId=$cardId lastGradedId=$lastGradedId phase=$phase")
        if (lastGradedId == cardId) {
            Log.d(TAG, "Ignoring duplicate grade for card $cardId")
            return
        }
        Log.d(TAG, "gradeAndAdvance: processing card ${card.noteId}:${card.cardOrd}")
        scope.launch {
            try {
                val timeTaken = System.currentTimeMillis() - cardStartTime
                Log.d(TAG, "gradeAndAdvance: calling answerFunction with ease=$ease, timeTaken=$timeTaken")
                val success = withContext(Dispatchers.IO) {
                    answerFunction?.invoke(card, ease, timeTaken) ?: false
                }
                Log.d(TAG, "gradeAndAdvance: answerFunction returned $success for card ${card.noteId}:${card.cardOrd}")
                if (success) {
                    lastGradedId = cardId
                    Log.d(TAG, "Graded card ${card.noteId}:${card.cardOrd} with ease $ease")
                    Log.d(TAG, "gradeAndAdvance: calling loadNextCard()")
                    loadNextCard()
                } else {
                    Log.w(TAG, "Failed to grade card")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error grading card", e)
            }
        }
    }
    
    fun showBack() {
        val card = currentCard ?: return
        if (phase != Phase.FRONT) return
        
        phase = Phase.BACK
        backShownMs = System.currentTimeMillis()
        
        Log.d(TAG, "Showing back for card ${card.noteId}:${card.cardOrd}")
        
        val backText = HtmlParser.extractDefinitionSummary(card.backHtml)
        glyphController.displaySmart(backText)
        
        card.backAudioPath?.let { audioPath ->
            val mp = MediaPreferences(context)
            Log.d(TAG, "Back audio request: enabled=${mp.isAudioEnabled()} tree=${mp.getMediaTreeUri()} path=$audioPath")
            audio.play(audioPath)
        } ?: Log.d(TAG, "No back audio for card ${card.noteId}:${card.cardOrd}")
        
        if (enableBackAutoAdvance) {
            mainHandler.postDelayed({
                if (phase == Phase.BACK && currentCard == card) {
                    gradeAndAdvance(2)
                }
            }, 3000)
        }
    }
    
    private suspend fun loadNextCard() {
        try {
            phase = Phase.TRANSITION
            val prev = currentCard?.let { (it.noteId shl 8) or it.cardOrd.toLong() }
            Log.d(TAG, "loadNextCard: prevCardId=$prev")
            val nextCard = withContext(Dispatchers.IO) { cardLoader?.invoke() }
            Log.d(TAG, "loadNextCard: loader returned noteOrd=${nextCard?.noteId}:${nextCard?.cardOrd}")
            if (nextCard != null) {
                withContext(Dispatchers.Main) {
                    currentCard = nextCard
                    showFront(nextCard)
                }
            } else {
                Log.d(TAG, "No more cards available")
                withContext(Dispatchers.Main) { glyphController.displaySmart("Fini") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading next card", e)
        }
    }
    
    private fun showFront(card: AnkiCard) {
        phase = Phase.FRONT
        cardStartTime = System.currentTimeMillis()
        
        Log.d(TAG, "Showing front for card ${card.noteId}:${card.cardOrd}")
        
        val frontText = HtmlParser.extractTextFromHtml(card.frontHtml)
        glyphController.displaySmart(frontText)
        
        card.frontAudioPath?.let { audioPath ->
            val mp = MediaPreferences(context)
            Log.d(TAG, "Front audio request: enabled=${mp.isAudioEnabled()} tree=${mp.getMediaTreeUri()} path=$audioPath")
            audio.play(audioPath)
        } ?: Log.d(TAG, "No front audio for card ${card.noteId}:${card.cardOrd}")
    }
    
    fun showCustom(text: String) {
        glyphController.displaySmart(text)
    }
    
    fun getCurrentPhase(): Phase = phase
    
    fun getCurrentCard(): AnkiCard? = currentCard
    
    fun onVolumeUp() {
        Log.d(TAG, "onVolumeUp: phase=$phase")
        val now = System.currentTimeMillis()
        if (now - lastKeyTime < keyDebounceMs) {
            Log.d(TAG, "onVolumeUp: debounced")
            return
        }
        lastKeyTime = now
        
        when (phase) {
            Phase.FRONT -> {
                Log.d(TAG, "onVolumeUp: FRONT->BACK transition")
                showBack()
            }
            Phase.BACK -> {
                val dwellTime = now - backShownMs
                if (dwellTime < minBackDwellMs) {
                    Log.d(TAG, "onVolumeUp: BACK dwell too short ($dwellTime < $minBackDwellMs)")
                    return
                }
                gradeAndAdvance(3) // Good
            }
            Phase.TRANSITION -> {
                Log.d(TAG, "onVolumeUp: in TRANSITION, ignoring")
            }
        }
    }
    
    fun onVolumeDown() {
        Log.d(TAG, "onVolumeDown: phase=$phase")
        val now = System.currentTimeMillis()
        if (now - lastKeyTime < keyDebounceMs) {
            Log.d(TAG, "onVolumeDown: debounced")
            return
        }
        lastKeyTime = now
        
        when (phase) {
            Phase.FRONT -> {
                Log.d(TAG, "onVolumeDown: FRONT->BACK transition")
                showBack()
            }
            Phase.BACK -> {
                val dwellTime = now - backShownMs
                if (dwellTime < minBackDwellMs) {
                    Log.d(TAG, "onVolumeDown: BACK dwell too short ($dwellTime < $minBackDwellMs)")
                    return
                }
                gradeAndAdvance(1) // Again
            }
            Phase.TRANSITION -> {
                Log.d(TAG, "onVolumeDown: in TRANSITION, ignoring")
            }
        }
    }
}
