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
import android.os.PowerManager

/**
 * Service that manages review sessions and AnkiDroid data loading (no notifications).
 */
class ReviewForegroundService : Service() {

    companion object {
        private const val TAG = "ReviewForegroundService"
        // Avoid immediately repeating the same card; block window in ms (session-local)
        private const val SAME_CARD_BLOCK_MS = 15_000L
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

    // Periodic lightweight refresh to keep AnkiDroid scheduler/counts up-to-date during a session
    private var refreshTickerJob: Job? = null

    private var screenDimWakeLock: PowerManager.WakeLock? = null

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

        // If a session is already active and for the same deck, restart it instead of ignoring
        if (reviewEngine != null && ReviewSessionState.active && ReviewSessionState.currentDeckId == deckId) {
            Log.w(TAG, "Session already active for deck=$deckId. Restarting session.")
            stopCurrentSession("Restart same deck")
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

    private fun applyScreenPolicyActiveIfEnabled() {
        try {
            val prefs = getSharedPreferences("display_prefs", Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("dim_screen_during_session", true)
            if (!enabled) return
            if (screenDimWakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "GlyphAnki:ReviewDim")
            wl.setReferenceCounted(false)
            wl.acquire()
            screenDimWakeLock = wl
            Log.d(TAG, "SCREEN_DIM wake lock acquired")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to acquire SCREEN_DIM wake lock", t)
        }
    }

    private fun clearScreenPolicy() {
        try {
            screenDimWakeLock?.let { wl ->
                if (wl.isHeld) wl.release()
                Log.d(TAG, "SCREEN_DIM wake lock released")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release SCREEN_DIM wake lock", t)
        } finally {
            screenDimWakeLock = null
        }
    }

    private fun stopCurrentSession(reason: String) {
        try {
            reviewEngine?.stopSession()
        } catch (_: Throwable) {
        } finally {
            reviewEngine = null
            // Stop periodic refresh ticker
            try { refreshTickerJob?.cancel() } catch (_: Throwable) {}
            refreshTickerJob = null
            clearScreenPolicy()
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
                // Strong reset of provider caches without sleeps
                ankiRepository.hardInvalidateProviderCaches(deckId)
                // Ensure selected deck is the target deck (provider switches back afterward if needed)
                runCatching { ankiRepository.selectDeck(deckId) }.onFailure { Log.w(TAG, "selectDeck failed", it) }
                ankiRepository.forceSyncRefresh()
                ankiRepository.preWarmDeckTop(deckId)
                // New: pre-fetch small queue to fully wake up provider for this deck
                runCatching { ankiRepository.peekQueue(deckId, limit = 3) }.onFailure { Log.w(TAG, "peekQueue warmup failed", it) }
                try {
                    contentResolver.query(
                        com.ichi2.anki.FlashCardsContract.Deck.CONTENT_ALL_URI,
                        arrayOf(com.ichi2.anki.FlashCardsContract.Deck.DECK_ID),
                        null, null, null
                    )?.use { }
                } catch (_: Exception) { }
            }
            Log.d(TAG, "Provider refresh done in ${System.currentTimeMillis() - tStart}ms")

            // Start/Restart a lightweight periodic refresh while the session is active
            refreshTickerJob?.cancel()
            refreshTickerJob = serviceScope.launch(Dispatchers.IO) {
                var tick = 0
                while (isActive) {
                    // Keep deck context and trigger minimal recompute
                    runCatching { ankiRepository.selectDeck(deckId) }
                    runCatching { ankiRepository.peekQueue(deckId, limit = 1) }
                    // Occasionally trigger a full refresh to keep reviewCount accurate
                    if (tick % 5 == 0) {
                        runCatching { ankiRepository.forceSyncRefresh() }
                    }
                    tick++
                    delay(2000)
                }
            }
            
            val fieldPrefs = FieldPreferences(this@ReviewForegroundService)
            val ffName = frontFieldName ?: "Front"
            val bfName = backFieldName ?: "Back"
            val ffFallbacks = frontFallbacks ?: emptyList()
            val bfFallbacks = backFallbacks ?: emptyList()
            val audioFront = fieldPrefs.getFrontAudioFields()
            val audioBack = fieldPrefs.getBackAudioFields()

            // Probe a first card (IO)
            var initial = withContext(Dispatchers.IO) {
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
            // If we got a malformed first card (empty front/back), perform a second refresh and retry once
            if (initial.isNotEmpty() && (initial[0].frontHtml.isBlank() && initial[0].backHtml.isBlank())) {
                withContext(Dispatchers.IO) { ankiRepository.forceSyncRefresh() }
                initial = withContext(Dispatchers.IO) {
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
                Log.d(TAG, "Retry after refresh, size=${initial.size}")
            }
            if (initial.isEmpty()) {
                val msg = "No cards available for review"
                Log.w(TAG, msg)
                sendStatusBroadcast(STATUS_ERROR, message = msg)
                stopSelf()
                return
            }

            reviewEngine = ReviewEngine(this@ReviewForegroundService)

            val ready = reviewEngine!!.waitGlyphReady(1200)
            Log.d(TAG, "Glyph ready before first frame: $ready")
            reviewEngine!!.warmUpGlyph()

            // Conflict handling flags shared between loader and answerer
            var needRefresh = false
            var lastFailedId: Long? = null
            var lastGradedId: Long? = null
            var lastShownId: Long? = null
            var lastGradeAtMs: Long = 0L
            var lastEaseWasAgain: Boolean = false
            // Blocklist for sids that should not be immediately repeated (sid = noteId<<8 | ord)
            val blockedUntil: MutableMap<Long, Long> = mutableMapOf()
            // Recent history (count-based, not time-based) to avoid ping-pong between 2 cards
            val recentSids: ArrayDeque<Long> = ArrayDeque()
            fun rememberSid(sid: Long) {
                recentSids.addLast(sid)
                while (recentSids.size > 8) recentSids.removeFirst()
            }
            // Keep track of all sids graded in this session; we avoid resurfacing them
            val gradedSids: MutableSet<Long> = LinkedHashSet()
            // Track consecutive empties to decide when deck is finished
            var consecutiveEmptyFetches = 0

            val smartLoader: suspend () -> AnkiCard? = {
                val t0 = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    if (needRefresh) {
                        Log.d(TAG, "smartLoader: needRefresh=true, HARD invalidation + refresh before load")
                        ankiRepository.hardInvalidateProviderCaches(deckId)
                        runCatching { ankiRepository.selectDeck(deckId) }.onFailure { }
                        ankiRepository.forceSyncRefresh()
                        runCatching { ankiRepository.peekQueue(deckId, limit = 10) }.onFailure { }
                        needRefresh = false
                    }

                    var attempt = 0
                    var picked: AnkiCard? = null
                    var lastBatchSize = 0
                    while (attempt < 3 && picked == null) {
                        val limit = when (attempt) {
                            0 -> 50
                            1 -> 75
                            else -> 100
                        }
                        var batch = ankiRepository.loadReviewCards(
                            deckId = deckId,
                            max = limit,
                            frontFieldName = ffName,
                            backFieldName = bfName,
                            frontFallbacks = ffFallbacks,
                            backFallbacks = bfFallbacks,
                            frontAudioFields = audioFront,
                            backAudioFields = audioBack
                        )
                        // Filter out any non-gradeable items (shouldn’t happen anymore, but be safe)
                        if (batch.isNotEmpty()) batch = batch.filter { it.gradeable }
                        lastBatchSize = batch.size

                        if (batch.isEmpty() && attempt == 0) {
                            Log.d(TAG, "smartLoader: empty batch -> force refresh path")
                            ankiRepository.hardInvalidateProviderCaches(deckId)
                            runCatching { ankiRepository.selectDeck(deckId) }
                            ankiRepository.forceSyncRefresh()
                            runCatching { ankiRepository.peekQueue(deckId, limit = 10) }
                            batch = ankiRepository.loadReviewCards(
                                deckId = deckId,
                                max = limit,
                                frontFieldName = ffName,
                                backFieldName = bfName,
                                frontFallbacks = ffFallbacks,
                                backFallbacks = bfFallbacks,
                                frontAudioFields = audioFront,
                                backAudioFields = audioBack
                            ).filter { it.gradeable }
                            lastBatchSize = batch.size
                        }

                        val topSid = batch.firstOrNull()?.let { (it.noteId shl 8) or (it.cardOrd.toLong() and 0xFF) }
                        if (lastGradedId != null && topSid == lastGradedId) {
                            Log.d(TAG, "smartLoader: top==lastGraded; forcing hard invalidation and refetch")
                            ankiRepository.hardInvalidateProviderCaches(deckId)
                            runCatching { ankiRepository.selectDeck(deckId) }.onFailure { }
                            ankiRepository.forceSyncRefresh()
                            batch = ankiRepository.loadReviewCards(
                                deckId = deckId,
                                max = limit,
                                frontFieldName = ffName,
                                backFieldName = bfName,
                                frontFallbacks = ffFallbacks,
                                backFallbacks = bfFallbacks,
                                frontAudioFields = audioFront,
                                backAudioFields = audioBack
                            ).filter { it.gradeable }
                            lastBatchSize = batch.size
                        }

                        val avoidSet = HashSet<Long>().apply {
                            lastFailedId?.let { add(it) }
                            if (!lastEaseWasAgain) {
                                lastShownId?.let { add(it) }
                                lastGradedId?.let { add(it) }
                            }
                            addAll(recentSids)
                            addAll(gradedSids)
                        }
                        val firstDifferent = batch.firstOrNull {
                            val sid = (it.noteId shl 8) or (it.cardOrd.toLong() and 0xFF)
                            !avoidSet.contains(sid)
                        }
                        picked = firstDifferent ?: batch.firstOrNull { card ->
                            val sid = (card.noteId shl 8) or (card.cardOrd.toLong() and 0xFF)
                            !gradedSids.contains(sid)
                        } ?: batch.getOrNull(1) ?: batch.firstOrNull()

                        Log.d(TAG, "smartLoader: fetched=${batch.size} limit=$limit attempt=$attempt picked=${picked?.noteId}:${picked?.cardOrd} avoidRecent=${recentSids.size} graded=${gradedSids.size}")

                        if (picked != null) {
                            val sid = (picked!!.noteId shl 8) or (picked!!.cardOrd.toLong() and 0xFF)
                            if (attempt == 0 && avoidSet.contains(sid)) {
                                Log.d(TAG, "smartLoader: picked avoided sid, hard invalidation + retry wider")
                                ankiRepository.hardInvalidateProviderCaches(deckId)
                                runCatching { ankiRepository.selectDeck(deckId) }.onFailure { }
                                ankiRepository.forceSyncRefresh()
                                picked = null
                            }
                        }
                        attempt++
                    }

                    if (picked == null && lastBatchSize == 0) {
                        consecutiveEmptyFetches++
                        Log.d(TAG, "smartLoader: consecutiveEmptyFetches=$consecutiveEmptyFetches")
                        runCatching { ankiRepository.selectDeck(deckId) }
                        runCatching { ankiRepository.peekQueue(deckId, limit = 1) }
                    } else if (picked != null) {
                        consecutiveEmptyFetches = 0
                    }

                    picked?.let { card ->
                        val sid = (card.noteId shl 8) or (card.cardOrd.toLong() and 0xFF)
                        lastShownId = sid
                        rememberSid(sid)
                    }
                    Log.d(TAG, "smartLoader: final picked=${picked?.noteId}:${picked?.cardOrd} in ${System.currentTimeMillis() - t0}ms")

                    // If we have no card twice in a row, consider deck finished for now (Engine will show End)
                    if (picked == null && consecutiveEmptyFetches >= 2) {
                        Log.d(TAG, "smartLoader: no cards twice -> signaling end of deck")
                    }
                    picked
                }
            }

            val answerFunction: suspend (AnkiCard, Int, Long) -> Boolean = { card, ease, timeTaken ->
                val t0 = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    val ok = ankiRepository.answerCard(card.noteId, card.cardOrd, ease, timeTaken)
                    Log.d(TAG, "answerFunction: ok=$ok for ${card.noteId}:${card.cardOrd} ease=$ease t=$timeTaken in ${System.currentTimeMillis() - t0}ms")
                    needRefresh = true
                    val sid = (card.noteId shl 8) or (card.cardOrd.toLong() and 0xFF)
                    if (ok) {
                        lastGradedId = sid
                        lastGradeAtMs = System.currentTimeMillis()
                        lastEaseWasAgain = ease <= 1
                        if (ease > 1) {
                            gradedSids.add(sid)
                        } else {
                            reviewEngine?.markRecentAgain(90_000L)
                            recentSids.removeIf { it == sid }
                        }
                        runCatching { ankiRepository.selectDeck(deckId) }
                        runCatching { ankiRepository.hardInvalidateProviderCaches(deckId) }
                        runCatching { ankiRepository.forceSyncRefresh() }
                        runCatching { ankiRepository.peekQueue(deckId, limit = 10) }
                    } else {
                        lastFailedId = sid
                    }
                    ok
                }
            }

            // Apply screen policy (keep on dim via wake lock). Window-level dim handled by Activity.
            applyScreenPolicyActiveIfEnabled()

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