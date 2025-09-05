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
import android.content.Intent
import com.alcyon.glyphanki.service.ReviewForegroundService
import android.app.Activity
import android.view.WindowManager

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
    private val keyDebounceMs = 40L // was 60L; lower for snappier input
    private var lastGradedId: Long = -1L
    private val minBackDwellMs = 120L // was 150L; keep safe but faster
    
    private var cardLoader: (suspend () -> AnkiCard?)? = null
    private var answerFunction: (suspend (AnkiCard, Int, Long) -> Boolean)? = null
    private var enableBackAutoAdvance = false
    private var stallAttempts: Int = 0
    private var consecutiveEmpty: Int = 0
    private var recentAgainUntilMs: Long = 0L
    private var endDisplayedAtMs: Long = 0L
    private var originalDimAmount: Float? = null

    private fun applyScreenPolicyActive() {
        try {
            val prefs = context.getSharedPreferences("display_prefs", Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("dim_screen_during_session", true)
            if (!enabled) return
            // We cannot directly access an Activity window from Service; use system flags on application window if available
            if (context is Activity) {
                val w = context.window
                originalDimAmount = w.attributes.screenBrightness
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val lp = w.attributes
                lp.screenBrightness = 0.01f // minimum brightness
                w.attributes = lp
            } else {
                // Best-effort: keep CPU/screen on via system flag if a visible Activity attaches later
                // No-op here; Activity should re-apply on resume if needed
            }
        } catch (t: Throwable) {
            Log.w(TAG, "applyScreenPolicyActive failed", t)
        }
    }

    private fun clearScreenPolicy() {
        try {
            if (context is Activity) {
                val w = context.window
                val lp = w.attributes
                lp.screenBrightness = originalDimAmount ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "clearScreenPolicy failed", t)
        } finally {
            originalDimAmount = null
        }
    }

    fun startSession(
        loader: suspend () -> AnkiCard?,
        answerer: suspend (AnkiCard, Int, Long) -> Boolean,
        autoAdvanceBack: Boolean = false
    ) {
        cardLoader = loader
        answerFunction = answerer
        enableBackAutoAdvance = autoAdvanceBack
        
        applyScreenPolicyActive()
        scope.launch {
            loadNextCard()
        }
    }
    
    fun stopSession() {
        Log.d(TAG, "Stopping review session")
        scope.cancel()
        glyphController.release()
        audio.release()
        clearScreenPolicy()
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
        Log.d(TAG, "gradeAndAdvance: processing card ${'$'}{card.noteId}:${'$'}{card.cardOrd}")
        scope.launch {
            try {
                val timeTaken = System.currentTimeMillis() - cardStartTime
                Log.d(TAG, "gradeAndAdvance: calling answerFunction with ease=$ease, timeTaken=$timeTaken")
                val success = withContext(Dispatchers.IO) {
                    answerFunction?.invoke(card, ease, timeTaken) ?: false
                }
                Log.d(TAG, "gradeAndAdvance: answerFunction returned $success for card ${'$'}{card.noteId}:${'$'}{card.cardOrd}")
                if (success) {
                    lastGradedId = cardId
                    Log.d(TAG, "Graded card ${'$'}{card.noteId}:${'$'}{card.cardOrd} with ease $ease")
                    Log.d(TAG, "gradeAndAdvance: calling loadNextCard()")
                    loadNextCard()
                } else {
                    Log.w(TAG, "Failed to grade card, skipping and refreshing queue")
                    // Show a brief notice and move on; the loader will refresh/avoid this card
                    withContext(Dispatchers.Main) { glyphController.displaySmart("Skipped") }
                    loadNextCard()
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
    
    fun markRecentAgain(windowMs: Long = 20000L) {
        recentAgainUntilMs = System.currentTimeMillis() + windowMs
    }

    private fun requestStopService() {
        try {
            clearScreenPolicy()
            val i = Intent(context, ReviewForegroundService::class.java)
            i.putExtra("action", ReviewForegroundService.ACTION_STOP)
            context.startService(i)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to request service stop", t)
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
                stallAttempts = 0
                consecutiveEmpty = 0
                endDisplayedAtMs = 0L
                withContext(Dispatchers.Main) {
                    currentCard = nextCard
                    showFront(nextCard)
                }
            } else {
                consecutiveEmpty++
                Log.d(TAG, "No more cards available (loader returned null) consecutiveEmpty=$consecutiveEmpty")
                val now = System.currentTimeMillis()
                val withinAgainWindow = now < recentAgainUntilMs
                if (consecutiveEmpty >= 2 && !withinAgainWindow) {
                    Log.d(TAG, "No cards twice and not within AGAIN window -> End")
                    withContext(Dispatchers.Main) { glyphController.displaySmart("End") }
                    endDisplayedAtMs = System.currentTimeMillis()
                    // Auto-stop after 10 seconds if still at End
                    mainHandler.postDelayed({
                        val shownFor = System.currentTimeMillis() - endDisplayedAtMs
                        if (endDisplayedAtMs != 0L && shownFor >= 10_000L) {
                            Log.d(TAG, "Auto-stopping session after End shown for ${'$'}shownFor ms")
                            requestStopService()
                        }
                    }, 10_000L)
                    return
                }
                withContext(Dispatchers.Main) { glyphController.displaySmart("Refreshing…") }
                delay(if (withinAgainWindow) 1200 else 400)
                loadNextCard()
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
