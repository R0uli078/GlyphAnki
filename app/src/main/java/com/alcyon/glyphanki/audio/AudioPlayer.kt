@file:Suppress("DirectSystemCurrentTimeMillisUsage")
package com.alcyon.glyphanki.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.os.HandlerThread
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.net.URLDecoder
import android.net.Uri
import com.alcyon.glyphanki.prefs.MediaPreferences
import androidx.documentfile.provider.DocumentFile
import android.content.res.AssetFileDescriptor
import java.io.FileOutputStream
import java.util.ArrayDeque
import android.media.AudioManager
import android.os.Build
import android.media.AudioFocusRequest
import android.os.SystemClock
import java.util.LinkedHashMap

class AudioPlayer(private val context: Context) {
    companion object {
        private const val TAG = "AudioPlayer"
        private const val DEBUG = false
    }
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioThread = HandlerThread("GlyphAnki-Audio").apply { start() }
    private val audioHandler = Handler(audioThread.looper)
    @Volatile private var player: MediaPlayer? = null
    // Keep an open descriptor when needed (e.g., unknown-length content URIs)
    @Volatile private var currentAfd: AssetFileDescriptor? = null
    @Volatile private var currentSource: String? = null
    @Volatile private var isPrepared: Boolean = false
    private var prepTimeout: Runnable? = null
    // Simple FIFO for multiple audio files on the same side
    private val pending = ArrayDeque<String>()
    // System services
    private val audioManager: AudioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    @Volatile private var focusRequest: AudioFocusRequest? = null
    // Generation counter to cancel stale searches
    @Volatile private var playSeq: Int = 0
    // Cache and base-dir for fast SAF resolution
    @Volatile private var cachedTreeUri: Uri? = null
    @Volatile private var mediaBaseDirUri: Uri? = null
    private val uriCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 256
    }

    fun play(path: String?) {
        // Skip entirely if audio disabled
        if (!MediaPreferences(context).isAudioEnabled()) return
        if (path.isNullOrBlank()) return
        // Cancel any queued work from previous plays
        audioHandler.removeCallbacksAndMessages(null)
        val seq = (++playSeq)
        audioHandler.post {
            if (seq != playSeq) {
                if (DEBUG) Log.d(TAG, "Play request superseded before start: " + path)
                return@post
            }
            // Time budget to avoid long delays (about 1.2s)
            val deadline = SystemClock.uptimeMillis() + 1200
            fun timedOut() = SystemClock.uptimeMillis() > deadline
            fun superseded() = seq != playSeq
            try {
                if (DEBUG) Log.d(TAG, "play request: " + path)
                // If nothing is playing but queue has stale items (from previous cards), clear it now
                if (player?.isPlaying != true && pending.isNotEmpty()) {
                    if (DEBUG) Log.d(TAG, "Clearing stale queue of size=" + pending.size)
                    pending.clear()
                }
                // If already playing, enqueue and return (avoid duplicates)
                player?.let { p ->
                    if (p.isPlaying) {
                        if (superseded()) return@post
                        // Ignore exact duplicate currently playing
                        if (currentSource == path) {
                            if (DEBUG) Log.d(TAG, "Already playing this source, ignoring duplicate: " + path)
                            return@post
                        }
                        if (pending.contains(path)) {
                            if (DEBUG) Log.d(TAG, "Duplicate already queued, ignoring: " + path)
                            return@post
                        }
                        pending.addLast(path)
                        if (DEBUG) Log.d(TAG, "Enqueued next audio. size=" + pending.size + " head=" + pending.peekFirst())
                        return@post
                    }
                }
                if (superseded()) return@post
                // Not playing: free player and start immediately
                releasePlayerOnly()
                // Ensure no old items linger
                if (pending.isNotEmpty()) {
                    if (DEBUG) Log.d(TAG, "Clearing queue before new start. size=" + pending.size)
                    pending.clear()
                }

                // If we have a cached resolution for this filename, use it first
                val candidateNames = buildCandidateNames(path)
                getCachedUri(candidateNames)?.let { cached ->
                    if (DEBUG) Log.d(TAG, "Cache hit for ${candidateNames.joinToString()} -> $cached")
                    if (tryPlay(cached)) return@post
                    else if (DEBUG) Log.d(TAG, "Cached source failed, will resolve again")
                }

                // 1) If already a content Uri, play directly
                if (path.startsWith("content://")) {
                    if (tryPlay(path)) return@post else if (DEBUG) Log.d(TAG, "Direct content play failed, will try other methods")
                }
                if (superseded() || timedOut()) return@post

                // 2) Try direct file access first (we have READ_EXTERNAL_STORAGE permission)
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    if (DEBUG) Log.d(TAG, "Direct file access: " + path)
                    if (tryPlay(path)) {
                        cachePut(candidateNames, path)
                        return@post
                    }
                }
                if (superseded() || timedOut()) return@post

                // 3) Try common AnkiDroid media paths
                val commonPaths = listOf(
                    "/storage/emulated/0/AnkiDroid/collection.media/$path",
                    "/storage/emulated/0/Android/data/com.ichi2.anki/files/AnkiDroid/collection.media/$path",
                    "/sdcard/AnkiDroid/collection.media/$path"
                )
                
                for (commonPath in commonPaths) {
                    if (superseded() || timedOut()) return@post
                    val commonFile = File(commonPath)
                    if (commonFile.exists() && commonFile.canRead()) {
                        if (DEBUG) Log.d(TAG, "Found in common path: " + commonPath)
                        if (tryPlay(commonPath)) {
                            cachePut(candidateNames, commonPath)
                            return@post
                        }
                    }
                }
                if (superseded() || timedOut()) return@post

                // 4) SAF: use persisted tree and resolve by filename (prefer baseDir docId; avoid enumeration)
                val names = candidateNames
                if (DEBUG) Log.d(TAG, "Candidate file names: " + names.joinToString())
                val safTree = MediaPreferences(context).getMediaTreeUri()
                if (DEBUG) Log.d(TAG, "SAF tree: " + safTree)
                if (safTree != null) {
                    // Reset caches if tree changed
                    if (cachedTreeUri?.toString() != safTree.toString()) {
                        if (DEBUG) Log.d(TAG, "Tree changed; clearing media cache")
                        uriCache.clear()
                        mediaBaseDirUri = null
                        cachedTreeUri = safTree
                    }

                    val hasPersisted = context.contentResolver.persistedUriPermissions.any { it.uri == safTree && it.isReadPermission }
                    if (DEBUG) Log.d(TAG, "SAF persisted read permission: " + hasPersisted)

                    // Resolve base dir once and cache it
                    val baseUri = ensureMediaBaseDir(safTree)
                    if (DEBUG) Log.d(TAG, "Base dir URI: " + baseUri)
                    if (baseUri != null) {
                        // Build child document URIs using base documentId
                        val baseDocId = runCatching { DocumentsContract.getDocumentId(baseUri) }.getOrNull()
                        if (DEBUG) Log.d(TAG, "Base docId: " + baseDocId)
                        if (!baseDocId.isNullOrBlank()) {
                            for (candidate in names) {
                                if (superseded() || timedOut()) return@post
                                val childDocId = "$baseDocId/$candidate"
                                val child = runCatching { DocumentsContract.buildDocumentUriUsingTree(safTree, childDocId) }.getOrNull()
                                if (child != null) {
                                    if (DEBUG) Log.d(TAG, "SAF direct child try: $childDocId -> $child")
                                    val src = child.toString()
                                    if (tryPlay(src)) {
                                        cachePut(names, src)
                                        return@post
                                    }
                                }
                            }
                        }
                    }
                    if (superseded() || timedOut()) return@post

                    // Fallback: DocumentFile API (may enumerate)
                    val treeDoc = DocumentFile.fromTreeUri(context, safTree)
                    if (DEBUG) Log.d(TAG, "Tree doc: name=" + (treeDoc?.name) + " isDir=" + (treeDoc?.isDirectory))
                    if (treeDoc != null && treeDoc.isDirectory) {
                        val baseDirDoc = when {
                            mediaBaseDirUri != null -> DocumentFile.fromTreeUri(context, safTree)?.let { root ->
                                if (root.name.equals("collection.media", true)) root else root.findFile("collection.media") ?: root
                            }
                            treeDoc.name.equals("collection.media", true) -> treeDoc
                            else -> treeDoc.findFile("collection.media") ?: treeDoc
                        }
                        if (DEBUG) Log.d(TAG, "Base dir chosen (doc): " + (baseDirDoc?.uri))
                        if (baseDirDoc != null) {
                            for (candidate in names) {
                                if (superseded() || timedOut()) return@post
                                val f = baseDirDoc.findFile(candidate)
                                if (DEBUG) Log.d(TAG, "findFile('" + candidate + "') -> " + (f?.uri))
                                if (f != null && f.isFile) {
                                    val src = f.uri.toString()
                                    if (DEBUG) Log.d(TAG, "SAF hit (DocumentFile): " + src)
                                    if (tryPlay(src)) {
                                        cachePut(names, src)
                                        return@post
                                    }
                                }
                            }
                        }
                    }

                    if (superseded() || timedOut()) return@post
                    // Last resort: enumerate immediate children of the tree root (slow)
                    val treeId = runCatching { DocumentsContract.getTreeDocumentId(safTree) }.getOrNull()
                    if (!treeId.isNullOrBlank()) {
                        val childrenUri = runCatching { DocumentsContract.buildChildDocumentsUriUsingTree(safTree, treeId) }.getOrNull()
                        if (childrenUri != null && !timedOut() && !superseded()) {
                            val cr = context.contentResolver
                            val proj = arrayOf(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME
                            )
                            cr.query(childrenUri, proj, null, null, null)?.use { c ->
                                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                                while (!timedOut() && !superseded() && c.moveToNext()) {
                                    val displayName = if (nameIdx >= 0) c.getString(nameIdx) else null
                                    if (displayName != null && names.any { it.equals(displayName, true) }) {
                                        val docId = if (idIdx >= 0) c.getString(idIdx) else null
                                        if (!docId.isNullOrBlank()) {
                                            val child = runCatching { DocumentsContract.buildDocumentUriUsingTree(safTree, docId) }.getOrNull()
                                            if (child != null) {
                                                val src = child.toString()
                                                if (DEBUG) Log.d(TAG, "SAF hit (enumerate): " + src)
                                                if (tryPlay(src)) {
                                                    cachePut(names, src)
                                                    return@post
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val isAllFiles = try { Environment.isExternalStorageManager() } catch (_: Throwable) { false }
                Log.w(TAG, "Audio playback failed via all methods. isAllFiles=" + isAllFiles + ". Requested=" + path)
            } catch (t: Throwable) {
                Log.e(TAG, "play error for " + path, t)
                releasePlayerOnly()
            }
        }
    }

    private fun tryPlay(source: String): Boolean {
        var mp: MediaPlayer? = null
        return try {
            // Extra device state diagnostics
            val am = audioManager
            val vol = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            val maxVol = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            val ringer = runCatching { am.ringerMode }.getOrNull()
            val musicActive = runCatching { am.isMusicActive }.getOrNull()
            if (DEBUG) Log.d(TAG, "Audio state: vol=$vol/$maxVol ringer=$ringer musicActive=$musicActive")

            mp = MediaPlayer()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            mp.setAudioAttributes(attrs)
            mp.setVolume(1.0f, 1.0f)
            isPrepared = false
            currentSource = source
            mp.setOnCompletionListener {
                if (DEBUG) Log.d(TAG, "onCompletion, source=" + source)
                audioHandler.post {
                    releasePlayerOnly()
                    startNextIfAny()
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra + " for " + source)
                audioHandler.post {
                    releasePlayerOnly()
                    startNextIfAny()
                }
                true
            }
            if (DEBUG) Log.d(TAG, "Trying dataSource: " + source)
            var configured = false
            if (source.startsWith("content://")) {
                val uri = Uri.parse(source)
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                if (DEBUG) Log.d(TAG, "Content URI mimeType=" + mime)
                configured = runCatching { mp.setDataSource(context, uri) }.isSuccess
                if (DEBUG) Log.d(TAG, "setDataSource(context, uri) success=" + configured)
                if (!configured) {
                    val afd = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
                    if (afd == null) {
                        Log.w(TAG, "openAssetFileDescriptor returned null for " + source)
                    } else {
                        val length = afd.length
                        val offset = afd.startOffset
                        if (DEBUG) Log.d(TAG, "AFD opened: length=" + length + " offset=" + offset)
                        configured = if (length >= 0L) {
                            runCatching {
                                mp.setDataSource(afd.fileDescriptor, offset, length)
                                try { afd.close() } catch (_: Throwable) {}
                            }.isSuccess
                        } else {
                            currentAfd = afd
                            runCatching { mp.setDataSource(afd.fileDescriptor) }.isSuccess
                        }
                        if (DEBUG) Log.d(TAG, "Configured via AFD: " + configured)
                    }
                    if (!configured) {
                        val cached = copyContentToCache(uri)
                        if (cached != null && cached.canRead()) {
                            if (DEBUG) Log.d(TAG, "Playing via cache file: " + cached.absolutePath)
                            configured = runCatching { mp.setDataSource(cached.absolutePath) }.isSuccess
                        } else {
                            Log.w(TAG, "Failed to create cache file for uri: " + uri)
                        }
                    }
                }
            } else {
                configured = runCatching { mp.setDataSource(source) }.isSuccess
            }
            if (!configured) {
                Log.w(TAG, "No data source configured for: " + source)
                try { mp.reset(); mp.release() } catch (_: Throwable) {}
                return false
            }

            // Request audio focus before starting
            var focusGranted = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val afr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { /* no-op */ }
                    .build()
                val res = audioManager.requestAudioFocus(afr)
                focusRequest = afr
                focusGranted = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            } else {
                val res = audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                focusGranted = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            }
            if (DEBUG) Log.d(TAG, "Audio focus granted=" + focusGranted)

            player = mp
            if (DEBUG) Log.d(TAG, "Calling prepare() synchronously")
            mp.prepare()
            isPrepared = true
            if (DEBUG) Log.i(TAG, "Prepared, starting playback: " + source)
            mp.start()
            // Probes to confirm playback progresses
            val mpRef = mp
            audioHandler.postDelayed({
                if (!DEBUG) return@postDelayed
                try {
                    val d = runCatching { mpRef.duration }.getOrNull()
                    val p = runCatching { mpRef.currentPosition }.getOrNull()
                    val playing = runCatching { mpRef.isPlaying }.getOrNull()
                    Log.d(TAG, "Playback probe t=120ms: isPlaying=$playing pos=$p ms dur=$d ms src=$source")
                } catch (_: Throwable) {}
            }, 120)
            audioHandler.postDelayed({
                if (!DEBUG) return@postDelayed
                try {
                    val d = runCatching { mpRef.duration }.getOrNull()
                    val p = runCatching { mpRef.currentPosition }.getOrNull()
                    val playing = runCatching { mpRef.isPlaying }.getOrNull()
                    Log.d(TAG, "Playback probe t=600ms: isPlaying=$playing pos=$p ms dur=$d ms src=$source")
                } catch (_: Throwable) {}
            }, 600)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setDataSource/prepare failed for " + source + ": " + t.javaClass.simpleName + ": " + t.message)
            try { mp?.reset(); mp?.release() } catch (_: Throwable) {}
            false
        }
    }

    private fun startNextIfAny() {
        if (pending.isEmpty()) {
            if (DEBUG) Log.d(TAG, "Queue empty; nothing to play next")
            return
        }
        val next = pending.removeFirst()
        if (DEBUG) Log.d(TAG, "Dequeued next audio: " + next + "; remaining size=" + pending.size)
        // Start next; this will not clear the remaining queue
        play(next)
    }

    private fun releasePlayerOnly() {
        try { player?.reset() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null
        // Abandon audio focus
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let {
                runCatching { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            }
        } else {
            runCatching { audioManager.abandonAudioFocus(null) }
        }
        try { currentAfd?.close() } catch (_: Throwable) {}
        currentAfd = null
        prepTimeout?.let { audioHandler.removeCallbacks(it) }
        prepTimeout = null
        isPrepared = false
        currentSource = null
    }

    private fun copyContentToCache(uri: Uri): File? {
        return try {
            val nameGuess = uri.lastPathSegment?.substringAfterLast('/') ?: ("audio_" + System.currentTimeMillis())
            val cacheDir = File(context.cacheDir, "glyphanki-audio").apply { mkdirs() }
            val outFile = File(cacheDir, nameGuess)
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(8 * 1024)
                    var total = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        output.write(buf, 0, r)
                        total += r
                    }
                    output.flush()
                    if (DEBUG) Log.d(TAG, "Cached " + total + " bytes to " + outFile.absolutePath)
                }
            }
            outFile
        } catch (t: Throwable) {
            Log.w(TAG, "copyContentToCache failed: " + t.javaClass.simpleName + ": " + t.message)
            null
        }
    }

    fun stop() { audioHandler.post { releaseInternal() } }
    fun pause() { audioHandler.post { player?.pause() } }
    fun release() { audioHandler.post { releaseInternal() } }

    private fun releaseInternal() {
        try { player?.reset() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null
        // Abandon audio focus
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let {
                runCatching { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            }
        } else {
            runCatching { audioManager.abandonAudioFocus(null) }
        }
        // Close any open AFD kept for content URIs
        try { currentAfd?.close() } catch (_: Throwable) {}
        currentAfd = null
        prepTimeout?.let { audioHandler.removeCallbacks(it) }
        prepTimeout = null
        isPrepared = false
        currentSource = null
        if (pending.isNotEmpty()) {
            if (DEBUG) Log.d(TAG, "Clearing queue on release. size=" + pending.size)
            pending.clear()
        }
    }

    private fun buildCandidateNames(path: String): List<String> {
        val name = File(path).name
        val decoded = try { URLDecoder.decode(name, "UTF-8") } catch (_: Throwable) { name }
        return if (decoded == name) listOf(name) else listOf(name, decoded)
    }

    private fun getCachedUri(names: List<String>): String? {
        for (n in names) {
            val key = n.lowercase()
            uriCache[key]?.let { return it }
        }
        return null
    }

    private fun cachePut(names: List<String>, uriStr: String) {
        names.forEach { n -> uriCache[n.lowercase()] = uriStr }
    }

    private fun ensureMediaBaseDir(safTree: Uri): Uri? {
        mediaBaseDirUri?.let { return it }
        val treeDoc = DocumentFile.fromTreeUri(context, safTree)
        if (treeDoc != null && treeDoc.isDirectory) {
            val baseDirDoc = when {
                treeDoc.name.equals("collection.media", true) -> treeDoc
                else -> treeDoc.findFile("collection.media") ?: treeDoc
            }
            mediaBaseDirUri = baseDirDoc.uri
            return mediaBaseDirUri
        }
        return null
    }
}


