@file:Suppress("DirectSystemCurrentTimeMillisUsage")
package com.alcyon.glyphanki.anki

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ichi2.anki.FlashCardsContract
import android.content.ContentValues
import android.content.ContentResolver
import android.database.Cursor
import android.util.Log
import java.io.File

private const val REVIEWINFO_CARD_ID = "cardId"

// Precompiled regex patterns for audio filename extraction
private val SOUND_TAG_REGEX = Regex("\\[sound:([^\\]]+)\\]", RegexOption.IGNORE_CASE)
private val AUDIO_SRC_REGEX = Regex("src=\\\"([^\\\"]+)\\\"", RegexOption.IGNORE_CASE)

data class AnkiCard(
    val id: Long,
    val frontHtml: String,
    val backHtml: String,
    val noteId: Long,
    val cardOrd: Int,
    val gradeable: Boolean,
    val frontAudioPath: String? = null,
    val backAudioPath: String? = null
)

class AnkiRepository(private val context: Context) {

    private fun ContentResolver.tryQuery(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = try { this.query(uri, projection, selection, selectionArgs, sortOrder) } catch (_: Throwable) { null }

    suspend fun listDecks(): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val resolver = context.contentResolver
        val decks = mutableListOf<Pair<Long, String>>()
        val cursor = resolver.tryQuery(
            FlashCardsContract.Deck.CONTENT_ALL_URI,
            arrayOf(FlashCardsContract.Deck.DECK_ID, FlashCardsContract.Deck.DECK_NAME),
            null, null, null
        )
        cursor?.use { c ->
            val idIdx = c.getColumnIndex(FlashCardsContract.Deck.DECK_ID)
            val nameIdx = c.getColumnIndex(FlashCardsContract.Deck.DECK_NAME)
            while (c.moveToNext()) {
                val id = if (idIdx >= 0) c.getLong(idIdx) else continue
                val name = if (nameIdx >= 0) c.getString(nameIdx) else "(unnamed deck)"
                decks.add(id to name)
            }
        }
        Log.d("AnkiRepository", "listDecks: ${decks.size} decks in ${System.currentTimeMillis() - t0}ms")
        decks
    }

    suspend fun getDeckNames(): List<String> = listDecks().map { it.second }

    suspend fun loadReviewCards(
        deckId: Long,
        max: Int = 50,
        frontFieldName: String = "Front",
        backFieldName: String = "Back",
        frontFallbacks: List<String> = emptyList(),
        backFallbacks: List<String> = emptyList(),
        frontAudioFields: List<String> = emptyList(),
        backAudioFields: List<String> = emptyList()
    ): List<AnkiCard> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        Log.d("AnkiRepository", "loadReviewCards: deck=$deckId max=$max front=$frontFieldName back=$backFieldName")
        val resolver = context.contentResolver
        val results = mutableListOf<AnkiCard>()
        val modelFieldNamesCache = mutableMapOf<Long, List<String>>() 

        Log.d("AnkiRepository", "schedule query: sel='deckID=?' args='[${deckId}]' sort='limit=${max}'")
        val scheduleCursor = resolver.tryQuery(
            FlashCardsContract.ReviewInfo.CONTENT_URI,
            arrayOf(
                FlashCardsContract.ReviewInfo.NOTE_ID,
                FlashCardsContract.ReviewInfo.CARD_ORD
            ),
            "deckID=?",
            arrayOf(deckId.toString()),
            "limit=${max}"
        )

        scheduleCursor?.use { cur ->
            val noteIdIdx = cur.getColumnIndex(FlashCardsContract.ReviewInfo.NOTE_ID)
            val ordIdx = cur.getColumnIndex(FlashCardsContract.ReviewInfo.CARD_ORD)
            var queued = 0
            var i = 0
            while (cur.moveToNext()) {
                val noteId = if (noteIdIdx >= 0) cur.getLong(noteIdIdx) else continue
                val ord = if (ordIdx >= 0) cur.getInt(ordIdx) else 0
                Log.d("AnkiRepository", "queue[$i]: note=$noteId ord=$ord")
                queued++; i++

                val noteUri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString())
                var mid = -1L
                var fldsJoined: String? = null
                val nCur = resolver.tryQuery(
                    noteUri,
                    arrayOf(FlashCardsContract.Note._ID, FlashCardsContract.Note.MID, FlashCardsContract.Note.FLDS),
                    null, null, null
                )
                nCur?.use { nc ->
                    if (nc.moveToFirst()) {
                        val midIdx = nc.getColumnIndex(FlashCardsContract.Note.MID)
                        val fldsIdx = nc.getColumnIndex(FlashCardsContract.Note.FLDS)
                        if (midIdx >= 0) mid = nc.getLong(midIdx)
                        if (fldsIdx >= 0) fldsJoined = nc.getString(fldsIdx)
                    }
                }
                Log.d("AnkiRepository", "note=$noteId ord=$ord mid=$mid flds.len=${fldsJoined?.length ?: -1}")

                var front = ""
                var back = ""
                var frontAudioPath: String? = null
                var backAudioPath: String? = null
                val fieldNames: List<String> = if (mid > 0) {
                    modelFieldNamesCache.getOrPut(mid) {
                        val modelUri = Uri.withAppendedPath(FlashCardsContract.Model.CONTENT_URI, mid.toString())
                        var names: List<String> = emptyList()
                        val mCur = resolver.tryQuery(modelUri, arrayOf(FlashCardsContract.Model.FIELD_NAMES), null, null, null)
                        mCur?.use { mc ->
                            if (mc.moveToFirst()) {
                                val fIdx = mc.getColumnIndex(FlashCardsContract.Model.FIELD_NAMES)
                                if (fIdx >= 0) names = (mc.getString(fIdx) ?: "").split('\u001F')
                            }
                        }
                        names
                    }
                } else emptyList()
                Log.d("AnkiRepository", "model mid=$mid fieldNames.count=${fieldNames.size}")

                val fields = (fldsJoined ?: "").split('\u001F')
                if (fieldNames.isNotEmpty() && fields.isNotEmpty() && fieldNames.size == fields.size) {
                    fun findIndex(name: String): Int {
                        val idx = fieldNames.indexOfFirst { it.equals(name, ignoreCase = true) }
                        return if (idx >= 0) idx else -1
                    }
                    fun findFirstIndex(names: List<String>, default: Int): Int {
                        names.forEach { n ->
                            val idx = findIndex(n)
                            if (idx >= 0) return idx
                        }
                        return default
                    }
                    val frontCandidates = listOf(frontFieldName, "Front") + frontFallbacks
                    val backCandidates = listOf(backFieldName, "Back") + backFallbacks
                    val fi = findFirstIndex(frontCandidates, 0)
                    val bi = findFirstIndex(backCandidates, if (fields.size > 1) 1 else 0)
                    front = fields.getOrNull(fi) ?: ""
                    back = fields.getOrNull(bi) ?: ""
                    Log.d("AnkiRepository", "field pick: fi=$fi bi=$bi front.len=${front.length} back.len=${back.length}")

                    if (frontAudioFields.isNotEmpty()) {
                        val ai = findFirstIndex(frontAudioFields, -1)
                        if (ai >= 0) frontAudioPath = buildMediaPathFromFieldValue(fields.getOrNull(ai))
                    }
                    if (backAudioFields.isNotEmpty()) {
                        val ai = findFirstIndex(backAudioFields, -1)
                        if (ai >= 0) backAudioPath = buildMediaPathFromFieldValue(fields.getOrNull(ai))
                    }
                }

                // Fallbacks
                var usedFallback = false
                if (front.isBlank() || back.isBlank()) {
                    val noteUri2 = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString())
                    val specificCardUri = Uri.withAppendedPath(Uri.withAppendedPath(noteUri2, "cards"), ord.toString())
                    val cCur = resolver.tryQuery(
                        specificCardUri,
                        arrayOf(
                            FlashCardsContract.Card.QUESTION_SIMPLE,
                            FlashCardsContract.Card.ANSWER_PURE,
                            FlashCardsContract.Card.QUESTION,
                            FlashCardsContract.Card.ANSWER
                        ),
                        null, null, null
                    )
                    cCur?.use { cc ->
                        if (cc.moveToFirst()) {
                            val qsIdx = cc.getColumnIndex(FlashCardsContract.Card.QUESTION_SIMPLE)
                            val apIdx = cc.getColumnIndex(FlashCardsContract.Card.ANSWER_PURE)
                            val qIdx = cc.getColumnIndex(FlashCardsContract.Card.QUESTION)
                            val aIdx = cc.getColumnIndex(FlashCardsContract.Card.ANSWER)
                            if (front.isBlank()) {
                                front = when {
                                    qsIdx >= 0 -> cc.getString(qsIdx) ?: front
                                    qIdx >= 0 -> cc.getString(qIdx) ?: front
                                    else -> front
                                }
                                usedFallback = true
                            }
                            if (back.isBlank()) {
                                back = when {
                                    apIdx >= 0 -> cc.getString(apIdx) ?: back
                                    aIdx >= 0 -> cc.getString(aIdx) ?: back
                                    else -> back
                                }
                                usedFallback = true
                            }
                        }
                    }
                }
                if (usedFallback) Log.d("AnkiRepository", "fallback used: front.len=${front.length} back.len=${back.length}")

                val syntheticId = (noteId shl 8) or (ord.toLong() and 0xFF)
                Log.d("AnkiRepository", "emit: sid=$syntheticId note=$noteId ord=$ord")
                results.add(AnkiCard(syntheticId, front, back, noteId, ord, gradeable = true, frontAudioPath = frontAudioPath, backAudioPath = backAudioPath))
            }
            Log.d("AnkiRepository", "loadReviewCards: queued=$queued built=${results.size}")
        }

        if (results.isEmpty()) {
            Log.d("AnkiRepository", "loadReviewCards: schedule empty, fallback by deck name")
            var deckName: String? = null
            val dCur = resolver.tryQuery(
                Uri.withAppendedPath(FlashCardsContract.Deck.CONTENT_ALL_URI, deckId.toString()),
                arrayOf(FlashCardsContract.Deck.DECK_NAME), null, null, null
            )
            dCur?.use { dc ->
                if (dc.moveToFirst()) {
                    val idx = dc.getColumnIndex(FlashCardsContract.Deck.DECK_NAME)
                    if (idx >= 0) deckName = dc.getString(idx)
                }
            }
            val name = deckName
            if (!name.isNullOrBlank()) {
                val nCur = resolver.tryQuery(
                    FlashCardsContract.Note.CONTENT_URI,
                    arrayOf(FlashCardsContract.Note._ID, FlashCardsContract.Note.MID, FlashCardsContract.Note.FLDS),
                    "deck:\"$name\"", null, null
                )
                nCur?.use { nc ->
                    var count = 0
                    while (nc.moveToNext() && count < max) {
                        val noteId = nc.getLong(nc.getColumnIndex(FlashCardsContract.Note._ID))
                        val mid = nc.getLong(nc.getColumnIndex(FlashCardsContract.Note.MID))
                        val fldsJoined = nc.getString(nc.getColumnIndex(FlashCardsContract.Note.FLDS))

                        val fieldNames: List<String> = if (mid > 0) {
                            modelFieldNamesCache.getOrPut(mid) {
                                val modelUri = Uri.withAppendedPath(FlashCardsContract.Model.CONTENT_URI, mid.toString())
                                var names: List<String> = emptyList()
                                val mCur = resolver.tryQuery(modelUri, arrayOf(FlashCardsContract.Model.FIELD_NAMES), null, null, null)
                                mCur?.use { mc ->
                                    if (mc.moveToFirst()) {
                                        val fIdx = mc.getColumnIndex(FlashCardsContract.Model.FIELD_NAMES)
                                        if (fIdx >= 0) names = (mc.getString(fIdx) ?: "").split('\u001F')
                                    }
                                }
                                names
                            }
                        } else emptyList()

                        var front = ""
                        var back = ""
                        var frontAudioPath: String? = null
                        var backAudioPath: String? = null
                        val fields = (fldsJoined ?: "").split('\u001F')
                        if (fieldNames.isNotEmpty() && fields.isNotEmpty() && fieldNames.size == fields.size) {
                            fun findIndex(name: String): Int {
                                val idx = fieldNames.indexOfFirst { it.equals(name, ignoreCase = true) }
                                return if (idx >= 0) idx else -1
                            }
                            val fi = findIndex(frontFieldName).let { if (it == -1) findIndex("Front") else it }.let { if (it == -1) 0 else it }
                            val bi = findIndex(backFieldName).let { if (it == -1) findIndex("Back") else it }.let { if (it == -1) (if (fields.size > 1) 1 else 0) else it }
                            front = fields.getOrNull(fi) ?: ""
                            back = fields.getOrNull(bi) ?: ""

                            if (frontAudioFields.isNotEmpty()) {
                                val ai = frontAudioFields.firstNotNullOfOrNull { name ->
                                    val idx = findIndex(name)
                                    if (idx >= 0) idx else null
                                } ?: -1
                                if (ai >= 0) frontAudioPath = buildMediaPathFromFieldValue(fields.getOrNull(ai))
                            }
                            if (backAudioFields.isNotEmpty()) {
                                val ai = backAudioFields.firstNotNullOfOrNull { name ->
                                    val idx = findIndex(name)
                                    if (idx >= 0) idx else null
                                } ?: -1
                                if (ai >= 0) backAudioPath = buildMediaPathFromFieldValue(fields.getOrNull(ai))
                            }
                        }

                        // Fallback to parse audio embedded in the text fields
                        if (frontAudioPath == null && front.isNotBlank()) frontAudioPath = buildMediaPathFromFieldValue(front)
                        if (backAudioPath == null && back.isNotBlank()) backAudioPath = buildMediaPathFromFieldValue(back)

                        var ord = 0
                        if (front.isBlank() || back.isBlank()) {
                            val noteUri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString())
                            val cardsUri = Uri.withAppendedPath(noteUri, "cards")
                            val cCur = resolver.tryQuery(
                                cardsUri,
                                arrayOf(
                                    FlashCardsContract.Card.CARD_ORD,
                                    FlashCardsContract.Card.QUESTION_SIMPLE,
                                    FlashCardsContract.Card.ANSWER_PURE,
                                    FlashCardsContract.Card.QUESTION,
                                    FlashCardsContract.Card.ANSWER
                                ),
                                null, null, null
                            )
                            cCur?.use { cc ->
                                if (cc.moveToFirst()) {
                                    val ordIdx = cc.getColumnIndex(FlashCardsContract.Card.CARD_ORD)
                                    ord = if (ordIdx >= 0) cc.getInt(ordIdx) else 0
                                    val qsIdx = cc.getColumnIndex(FlashCardsContract.Card.QUESTION_SIMPLE)
                                    val apIdx = cc.getColumnIndex(FlashCardsContract.Card.ANSWER_PURE)
                                    val qIdx = cc.getColumnIndex(FlashCardsContract.Card.QUESTION)
                                    val aIdx = cc.getColumnIndex(FlashCardsContract.Card.ANSWER)
                                    if (front.isBlank()) {
                                        front = when {
                                            qsIdx >= 0 -> cc.getString(qsIdx) ?: front
                                            qIdx >= 0 -> cc.getString(qIdx) ?: front
                                            else -> front
                                        }
                                    }
                                    if (back.isBlank()) {
                                        back = when {
                                            apIdx >= 0 -> cc.getString(apIdx) ?: back
                                            aIdx >= 0 -> cc.getString(aIdx) ?: back
                                            else -> back
                                        }
                                    }
                                }
                            }
                        }

                        val syntheticId = (noteId shl 8) or (ord.toLong() and 0xFF)
                        results.add(AnkiCard(syntheticId, front, back, noteId, ord, gradeable = false, frontAudioPath = frontAudioPath, backAudioPath = backAudioPath))
                        count++
                    }
                }
            }
        }

        Log.d("AnkiRepository", "loadReviewCards: returning ${results.size} in ${System.currentTimeMillis() - t0}ms")
        results
    }

    // Diagnostics: peek top N items from schedule for a deck
    suspend fun peekQueue(deckId: Long, limit: Int = 5): List<Pair<Long, Int>> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val out = mutableListOf<Pair<Long, Int>>()
        val c = resolver.tryQuery(
            FlashCardsContract.ReviewInfo.CONTENT_URI,
            arrayOf(FlashCardsContract.ReviewInfo.NOTE_ID, FlashCardsContract.ReviewInfo.CARD_ORD),
            "deckID=?",
            arrayOf(deckId.toString()),
            "limit=${limit}"
        )
        c?.use { cur ->
            val nIdx = cur.getColumnIndex(FlashCardsContract.ReviewInfo.NOTE_ID)
            val oIdx = cur.getColumnIndex(FlashCardsContract.ReviewInfo.CARD_ORD)
            var i = 0
            while (cur.moveToNext() && i < limit) {
                val nid = if (nIdx >= 0) cur.getLong(nIdx) else -1L
                val ord = if (oIdx >= 0) cur.getInt(oIdx) else -1
                if (nid >= 0 && ord >= 0) out.add(nid to ord)
                i++
            }
        }
        Log.d("AnkiRepository", "peekQueue deck=$deckId top=${out.joinToString { "${'$'}{it.first}:${'$'}{it.second}" }}")
        out
    }

    suspend fun forceSyncRefresh() = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val resolver = context.contentResolver
        try {
            val cursor = resolver.tryQuery(
                FlashCardsContract.Deck.CONTENT_ALL_URI,
                arrayOf(FlashCardsContract.Deck.DECK_ID),
                null, null, null
            )
            val count = cursor?.use { it.count } ?: -1
            Log.d("AnkiRepository", "forceSyncRefresh: decks=$count took ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            Log.w("AnkiRepository", "forceSyncRefresh failed", e)
        }
    }

    suspend fun answerCard(noteId: Long, cardOrd: Int, ease: Int, timeTakenMs: Long): Boolean = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val resolver = context.contentResolver
        Log.d("AnkiRepository", "answerCard: sending note=$noteId ord=$cardOrd ease=$ease t=$timeTakenMs thread=${Thread.currentThread().name}")
        val values = ContentValues().apply {
            put(FlashCardsContract.ReviewInfo.NOTE_ID, noteId)
            put(FlashCardsContract.ReviewInfo.CARD_ORD, cardOrd)
            put(FlashCardsContract.ReviewInfo.EASE, ease)
            put(FlashCardsContract.ReviewInfo.TIME_TAKEN, timeTakenMs)
        }
        val first = try {
            resolver.update(FlashCardsContract.ReviewInfo.CONTENT_URI, values, null, null)
        } catch (e: Exception) {
            Log.w("AnkiRepository", "answerCard: first update failed note=$noteId ord=$cardOrd ease=$ease t=$timeTakenMs", e)
            -1
        }
        if (first > 0) {
            Log.d("AnkiRepository", "answerCard: ok rows=$first note=$noteId ord=$cardOrd ease=$ease t=$timeTakenMs in ${System.currentTimeMillis() - t0}ms")
            return@withContext true
        }
        val second = try {
            resolver.update(FlashCardsContract.ReviewInfo.CONTENT_URI, values, null, null)
        } catch (e: Exception) {
            Log.w("AnkiRepository", "answerCard: retry failed note=$noteId ord=$cardOrd ease=$ease t=$timeTakenMs", e)
            -1
        }
        Log.d("AnkiRepository", "answerCard: retry rows=$second note=$noteId ord=$cardOrd ease=$ease t=$timeTakenMs total ${System.currentTimeMillis() - t0}ms")
        return@withContext second > 0
    }

    private fun buildMediaPathFromFieldValue(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val filename = extractAudioFilename(value) ?: return null
        // Return only the filename; actual file resolution is done via the SAF folder the user selected
        return filename
    }

    private fun extractAudioFilename(value: String): String? {
        // Prefer [sound:...] tag
        val bracket = SOUND_TAG_REGEX.find(value)?.groupValues?.getOrNull(1)
        if (!bracket.isNullOrBlank()) return lastSegment(bracket.trim())
        // Try HTML <audio src="...">
        val src = AUDIO_SRC_REGEX.find(value)?.groupValues?.getOrNull(1)
        if (!src.isNullOrBlank()) return lastSegment(src.trim())
        // If plain filename present
        val plain = value.trim()
        if (plain.contains('.')) return lastSegment(plain)
        return null
    }

    private fun lastSegment(name: String): String {
        return name.substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
    }
}
