@file:Suppress("DirectSystemCurrentTimeMillisUsage")
package com.alcyon.glyphanki.service

import android.app.Service
import android.content.Intent
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.alcyon.glyphanki.anki.AnkiRepository
import com.alcyon.glyphanki.anki.AnkiCard
import com.alcyon.glyphanki.prefs.FieldPreferences
import com.alcyon.glyphanki.session.ReviewEngine
import com.alcyon.glyphanki.session.ReviewSessionState
import kotlinx.coroutines.*
import java.util.*

/**
 * Service that manages review sessions and AnkiDroid data loading (no notifications).
 */
class ReviewForegroundService : Service() {

    companion object {
        private const val TAG = "ReviewForegroundService"
        // Broadcast actions for UI messages
        const val BROADCAST_ACTION = "com.alcyon.glyphanki.REVIEW_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_REASON = "reason"
        const val STATUS_STARTED = "started"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_ERROR = "error"
        
        // Intent extras for starting sessions
        const val EXTRA_DECK_ID = "deck_id"
        const val EXTRA_FRONT_FIELD = "front_field"
        const val EXTRA_BACK_FIELD = "back_field"
        const val EXTRA_FRONT_FALLBACKS = "front_fallbacks"
        const val EXTRA_BACK_FALLBACKS = "back_fallbacks"
        const val EXTRA_ENABLE_BACK_AUTO_ADVANCE = "enable_back_auto_advance"
        
        // Control actions
        const val ACTION_STOP = "com.alcyon.glyphanki.action.STOP"
        const val ACTION_VOL_UP = "VOL_UP"
        const val ACTION_VOL_DOWN = "VOL_DOWN"
        
        fun startSessionForDeck(
            context: Context,
            deckId: Long,
            frontFieldName: String? = null,
            backFieldName: String? = null,
            frontFallbacks: List<String>? = null,
            backFallbacks: List<String>? = null,
            enableBackAutoAdvance: Boolean = false
        ) {
            val intent = Intent(context, ReviewForegroundService::class.java).apply {
                putExtra(EXTRA_DECK_ID, deckId)
                frontFieldName?.let { putExtra(EXTRA_FRONT_FIELD, it) }
                backFieldName?.let { putExtra(EXTRA_BACK_FIELD, it) }
                frontFallbacks?.let { putStringArrayListExtra(EXTRA_FRONT_FALLBACKS, ArrayList(it)) }
                backFallbacks?.let { putStringArrayListExtra(EXTRA_BACK_FALLBACKS, ArrayList(it)) }
                putExtra(EXTRA_ENABLE_BACK_AUTO_ADVANCE, enableBackAutoAdvance)
            }
            context.startService(intent)
        }
    }

    inner class ServiceBinder : Binder() {
        fun getService(): ReviewForegroundService = this@ReviewForegroundService
    }

    private val binder = ServiceBinder()
    private var serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private lateinit var ankiRepository: AnkiRepository
    var reviewEngine: ReviewEngine? = null
        private set

    override fun onCreate() {
        super.onCreate()
        ankiRepository = AnkiRepository(this)
        // Quiet logs
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle control actions first (quiet logs)
        val action = intent?.getStringExtra("action")
        if (action != null) {
            when (action) {
                ACTION_VOL_UP -> {
                    reviewEngine?.onVolumeUp()
                    return START_NOT_STICKY
                }
                ACTION_VOL_DOWN -> {
                    reviewEngine?.onVolumeDown()
                    return START_NOT_STICKY
                }
                ACTION_STOP -> {
                    stopCurrentSession("User stop")
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        
        // Extract session parameters
        if (intent == null) {
            Log.e(TAG, "No intent provided, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        val deckId = intent.getLongExtra(EXTRA_DECK_ID, -1L)
        if (deckId == -1L) {
            Log.e(TAG, "No deck ID provided, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        // If a session is already active and for the same deck, ignore duplicate start
        if (reviewEngine != null && ReviewSessionState.active && ReviewSessionState.currentDeckId == deckId) {
            Log.w(TAG, "Session already active for deck=$deckId. Ignoring duplicate start.")
            return START_STICKY
        }
        // If a different session is active, stop it first
        if (reviewEngine != null) {
            Log.w(TAG, "Another session is running (deck=${ReviewSessionState.currentDeckId}); restarting with deck=$deckId")
            stopCurrentSession("Restart with new deck")
        }

        val frontFieldName = intent.getStringExtra(EXTRA_FRONT_FIELD)
        val backFieldName = intent.getStringExtra(EXTRA_BACK_FIELD)
        val frontFallbacks = intent.getStringArrayListExtra(EXTRA_FRONT_FALLBACKS)
        val backFallbacks = intent.getStringArrayListExtra(EXTRA_BACK_FALLBACKS)
        val enableBackAutoAdvance = intent.getBooleanExtra(EXTRA_ENABLE_BACK_AUTO_ADVANCE, false)

        // Start the review session
        serviceScope.launch {
            startReviewSession(
                deckId, frontFieldName, backFieldName, 
                frontFallbacks, backFallbacks, enableBackAutoAdvance
            )
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        stopCurrentSession("Service destroyed")
        super.onDestroy()
    }

    private fun stopCurrentSession(reason: String) {
        try {
            reviewEngine?.stopSession()
        } catch (_: Throwable) {
        } finally {
            reviewEngine = null
            serviceScope.cancel()
            serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            ReviewSessionState.clear()
            sendStatusBroadcast(STATUS_STOPPED, reason = reason)
        }
    }

    private suspend fun startReviewSession(
        deckId: Long,
        frontFieldName: String?,
        backFieldName: String?,
        frontFallbacks: List<String>?,
        backFallbacks: List<String>?,
        enableBackAutoAdvance: Boolean
    ) {
        try {
            val tStart = System.currentTimeMillis()
            Log.d(TAG, "Starting review session for deck $deckId")

            withContext(Dispatchers.IO) {
                ankiRepository.forceSyncRefresh()
            }
            Log.d(TAG, "Provider refresh done in ${System.currentTimeMillis() - tStart}ms")
            
            val fieldPrefs = FieldPreferences(this@ReviewForegroundService)
            val ffName = frontFieldName ?: "Front"
            val bfName = backFieldName ?: "Back"
            val ffFallbacks = frontFallbacks ?: emptyList()
            val bfFallbacks = backFallbacks ?: emptyList()
            val audioFront = fieldPrefs.getFrontAudioFields()
            val audioBack = fieldPrefs.getBackAudioFields()

            // Probe a first card (IO)
            val initial = withContext(Dispatchers.IO) {
                ankiRepository.loadReviewCards(
                    deckId = deckId,
                    max = 1,
                    frontFieldName = ffName,
                    backFieldName = bfName,
                    frontFallbacks = ffFallbacks,
                    backFallbacks = bfFallbacks,
                    frontAudioFields = audioFront,
                    backAudioFields = audioBack
                )
            }
            Log.d(TAG, "Initial load size=${initial.size} after ${System.currentTimeMillis() - tStart}ms")
            if (initial.isEmpty()) {
                val msg = "No cards available for review"
                Log.w(TAG, msg)
                sendStatusBroadcast(STATUS_ERROR, message = msg)
                stopSelf()
                return
            }

            reviewEngine = ReviewEngine(this@ReviewForegroundService)

            val ready = reviewEngine!!.waitGlyphReady(1800)
            Log.d(TAG, "Glyph ready before first frame: $ready")
            reviewEngine!!.warmUpGlyph()
            delay(120)

            val smartLoader: suspend () -> AnkiCard? = {
                val t0 = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    val fresh = ankiRepository.loadReviewCards(
                        deckId = deckId,
                        max = 1,
                        frontFieldName = ffName,
                        backFieldName = bfName,
                        frontFallbacks = ffFallbacks,
                        backFallbacks = bfFallbacks,
                        frontAudioFields = audioFront,
                        backAudioFields = audioBack
                    )
                    Log.d(TAG, "smartLoader: fetched=${fresh.size} in ${System.currentTimeMillis() - t0}ms")
                    fresh.firstOrNull()
                }
            }

            val answerFunction: suspend (AnkiCard, Int, Long) -> Boolean = { card, ease, timeTaken ->
                val t0 = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    val ok = ankiRepository.answerCard(card.noteId, card.cardOrd, ease, timeTaken)
                    Log.d(TAG, "answerFunction: ok=$ok for ${card.noteId}:${card.cardOrd} ease=$ease t=$timeTaken in ${System.currentTimeMillis() - t0}ms")
                    ok
                }
            }

            reviewEngine?.startSession(smartLoader, answerFunction, enableBackAutoAdvance)
            ReviewSessionState.setActive(deckId)
            sendStatusBroadcast(STATUS_STARTED)

        } catch (e: Exception) {
            val msg = "Failed to start review session: ${e.message}"
            Log.e(TAG, msg, e)
            sendStatusBroadcast(STATUS_ERROR, message = msg)
            stopSelf()
        }
    }

    private fun sendStatusBroadcast(status: String, message: String? = null, reason: String? = null) {
        val i = Intent(BROADCAST_ACTION)
        i.putExtra(EXTRA_STATUS, status)
        if (!message.isNullOrBlank()) i.putExtra(EXTRA_MESSAGE, message)
        if (!reason.isNullOrBlank()) i.putExtra(EXTRA_REASON, reason)
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }
}