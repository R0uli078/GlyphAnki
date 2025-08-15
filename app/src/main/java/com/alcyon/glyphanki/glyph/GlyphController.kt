package com.alcyon.glyphanki.glyph

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixFrame
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphMatrixObject
import android.graphics.Typeface
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private object Log {
    fun d(tag: String, msg: String) {}
    fun w(tag: String, msg: String) {}
    fun e(tag: String, msg: String) {}
}

class GlyphController(private val context: Context) {
    private val manager: GlyphMatrixManager = GlyphMatrixManager.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var isInitialized: Boolean = false
    @Volatile private var isRegistered: Boolean = false
    private val pendingActions: MutableList<(GlyphMatrixManager) -> Unit> = mutableListOf()
    @Volatile private var stopRequested: Boolean = false
    @Volatile private var scrollingThread: Thread? = null
    private val scrollLock = Any()
    private var didWakeOnce = false
    @Volatile private var needsWakeAfterStop = false
    @Volatile private var keepAliveThread: Thread? = null
    private val TAG = "GlyphAnki/Glyph"

    private data class OffscreenRender(val bitmap: Bitmap, val contentWidth: Int)

    // Helper: prefers setAppMatrixFrame; falls back to setMatrixFrame(render()) on older OS
    private fun safeSetFrame(m: GlyphMatrixManager, frame: GlyphMatrixFrame) {
        Log.d(TAG, "safeSetFrame: trying setAppMatrixFrame")
        val ok = kotlin.runCatching { m.setAppMatrixFrame(frame); true }
            .onFailure { Log.w(TAG, "setAppMatrixFrame failed: ${it.message}") }
            .getOrElse { false }
        if (ok) {
            Log.d(TAG, "safeSetFrame: setAppMatrixFrame succeeded")
        } else {
            Log.d(TAG, "safeSetFrame: falling back to setMatrixFrame(render())")
            kotlin.runCatching { m.setMatrixFrame(frame.render()); Log.d(TAG, "safeSetFrame: setMatrixFrame(render()) succeeded") }
                .onFailure { Log.e(TAG, "setMatrixFrame(render()) failed: ${it.message}") }
        }
    }

    // Init/registration management
    private val callback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(name: ComponentName) {
            Log.d(TAG, "onServiceConnected: $name")
            isInitialized = true
            tryRegisterAndFlush()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "onServiceDisconnected: $name")
            isInitialized = false
            isRegistered = false
            didWakeOnce = false
        }
    }

    private fun tryRegisterAndFlush() {
        try {
            Log.d(TAG, "tryRegisterAndFlush: isRegistered=$isRegistered, pendingActions=${pendingActions.size}, didWakeOnce=$didWakeOnce")
            if (!isRegistered) {
                var ok = false
                kotlin.runCatching { manager.register(Glyph.DEVICE_23112) }
                    .onSuccess { ok = true; Log.d(TAG, "register(23112) ok") }
                    .onFailure { Log.w(TAG, "register(23112) failed: ${it.message}") }
                if (!ok) {
                    kotlin.runCatching { manager.register(Glyph.DEVICE_24111) }
                        .onSuccess { ok = true; Log.d(TAG, "register(24111) ok") }
                        .onFailure { Log.w(TAG, "register(24111) failed: ${it.message}") }
                }
                isRegistered = ok
                if (!ok) { Log.w(TAG, "registration failed; aborting flush") ; return }
            }
            if (!didWakeOnce) {
                Log.d(TAG, "tryRegisterAndFlush: first wake blank frame")
                didWakeOnce = true
                safeSetFrame(manager, com.nothing.ketchum.GlyphMatrixFrame.Builder().build(context))
                try { Thread.sleep(60) } catch (_: Throwable) {}
            }
            val actions = pendingActions.toList()
            pendingActions.clear()
            if (actions.isNotEmpty()) Log.d(TAG, "tryRegisterAndFlush: flushing ${actions.size} pending actions")
            actions.forEach { action ->
                runCatching { action(manager) }
                    .onFailure { Log.e(TAG, "pending action failed: ${it.message}") }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "tryRegisterAndFlush: exception: ${t.message}")
        }
    }

    private fun ensureReady(action: (GlyphMatrixManager) -> Unit) {
        Log.d(TAG, "ensureReady: init=$isInitialized reg=$isRegistered needsWake=$needsWakeAfterStop")
        if (!isInitialized) {
            Log.d(TAG, "ensureReady: enqueue action and init()")
            pendingActions.add(action)
            runCatching { manager.init(callback) }
                .onFailure { Log.e(TAG, "init() failed: ${it.message}") }
            return
        }
        if (!isRegistered) {
            Log.d(TAG, "ensureReady: enqueue action and tryRegisterAndFlush()")
            pendingActions.add(action)
            tryRegisterAndFlush()
            return
        }
        if (needsWakeAfterStop) {
            Log.d(TAG, "ensureReady: needsWakeAfterStop -> sending blank wake frame")
            needsWakeAfterStop = false
            // Single quick blank frame only after explicit stop
            safeSetFrame(manager, GlyphMatrixFrame.Builder().build(context))
            try { Thread.sleep(50) } catch (_: Throwable) {}
        }
        action(manager)
    }

    // Clear current frame but keep service alive (for quick switching)
    private fun clearDisplay() {
        Log.d(TAG, "clearDisplay: blank frame")
        runCatching { safeSetFrame(manager, GlyphMatrixFrame.Builder().build(context)) }
            .onFailure { Log.e(TAG, "clearDisplay failed: ${it.message}") }
    }

    // Force reinitialize manager (for rotation recovery)
    fun reinitialize() {
        Log.w(TAG, "reinitialize: force re-init")
        stopScrolling()
        clearDisplay()
        isInitialized = false
        isRegistered = false
        didWakeOnce = false
        pendingActions.clear()
        kotlin.runCatching { manager.init(callback) }
            .onFailure { Log.e(TAG, "reinitialize init() failed: ${it.message}") }
    }

    // Display simple ASCII text using setText with persistent manager
    fun displayText(text: String) {
        Log.d(TAG, "displayText: '${text}'")
        // no toast
        ensureReady { glyphManager ->
            clearDisplay()
            displayTextAction(glyphManager, text)
        }
    }

    private fun displayTextAction(glyphManager: com.nothing.ketchum.GlyphMatrixManager, text: String) {
        try {
            Log.d(TAG, "displayTextAction(setText) start: len=${text.length}")
            // setText path
            val objectBuilder = GlyphMatrixObject.Builder()
            val glyphObject = objectBuilder
                .setText(text)
                .setScale(40)
                .setOrientation(0)
                .setPosition(3, 9)
                .setBrightness(255)
                .build()

            val frameBuilder = GlyphMatrixFrame.Builder()
            val frame = frameBuilder.addTop(glyphObject).build(context)

            safeSetFrame(glyphManager, frame)
            Log.d(TAG, "displayTextAction: frame set via setText path")
        } catch (e: Exception) {
            Log.w(TAG, "displayTextAction(setText) failed, fallback to bitmap: ${e.message}")
            // Fallback to bitmap
            try {
                val bitmap = Bitmap.createBitmap(25, 25, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 8f
                    isAntiAlias = false
                    isSubpixelText = false
                    flags = flags and Paint.SUBPIXEL_TEXT_FLAG.inv()
                    textAlign = Paint.Align.LEFT
                    style = Paint.Style.FILL
                    typeface = android.graphics.Typeface.SANS_SERIF
                }
                val fm = paint.fontMetrics
                val baseline = kotlin.math.round(((25f - (fm.bottom - fm.top)) / 2f - fm.top)).toFloat()
                val w = paint.measureText(text)
                val sx = kotlin.math.round(((25f - w) / 2f)).toFloat().coerceAtLeast(0f)
                canvas.drawText(text, sx, baseline, paint)

                val bitmapObjectBuilder = GlyphMatrixObject.Builder()
                val bitmapObject = bitmapObjectBuilder
                    .setImageSource(bitmap)
                    .setScale(100)
                    .setOrientation(0)
                    .setPosition(0, 0)
                    .setBrightness(255)
                    .build()

                val bitmapFrameBuilder = GlyphMatrixFrame.Builder()
                val bitmapFrame = bitmapFrameBuilder.addTop(bitmapObject).build(context)

                safeSetFrame(glyphManager, bitmapFrame)
                Log.d(TAG, "displayTextAction(bitmap) frame set")
            } catch (ex: Exception) {
                Log.e(TAG, "displayTextAction(bitmap) failed: ${ex.message}")
            }
        }
    }

    // Public methods for AnkiDroid card display (optimized rendering)
    fun showFront(text: String) = displaySmart(text)
    fun showBack(text: String) = displaySmart(text)

    // Method that renders text to bitmap (universal solution)
    fun displayTextAsBitmap(text: String) {
        Log.d(TAG, "displayTextAsBitmap: len=${text.length}")
        ensureReady { glyphManager ->
            stopScrolling()
            clearDisplay()
            renderTextToBitmap(glyphManager, text)
        }
    }

    // Smart display: ASCII-only uses setText, otherwise high-quality bitmap; scroll if needed
    fun displaySmart(text: String) {
        Log.d(TAG, "displaySmart: len=${text.length} ascii=${text.all { it.code in 32..126 }}")
        val isAscii = text.all { it.code in 32..126 }
        if (isAscii) {
            ensureReady { glyphManager ->
                stopScrolling()
                clearDisplay()
                val off = renderCleanAsciiOffscreen(text)
                if (off.contentWidth <= 25) {
                    val target = android.graphics.Bitmap.createBitmap(25, 25, android.graphics.Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(target)
                    val centerX = (25 - off.contentWidth) / 2f
                    val sourceX = 8f
                    val destX = centerX - sourceX
                    c.drawBitmap(off.bitmap, destX, 0f, null)
                    val obj = com.nothing.ketchum.GlyphMatrixObject.Builder().setImageSource(target).setScale(100).setOrientation(0).setPosition(0, 0).setBrightness(255).build()
                    val frame = com.nothing.ketchum.GlyphMatrixFrame.Builder().addTop(obj).build(context)
                    safeSetFrame(glyphManager, frame)
                    Log.d(TAG, "displaySmart(ascii, static) frame set")
                } else {
                    Log.d(TAG, "displaySmart(ascii) -> scrolling, width=${off.contentWidth}")
                    displayScrollingBitmap(off.bitmap, speedMs = 24L, loop = true) // requested 24 ms/pixel
                }
            }
            return
        }
        ensureReady { glyphManager ->
            stopScrolling()
            clearDisplay()
            val off = renderMixedOffscreen(text)
            if (off.contentWidth <= 25) {
                val target = android.graphics.Bitmap.createBitmap(25, 25, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(target)
                val face = pickThinnestTypeface(text)
                val p = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 8f
                    isAntiAlias = false
                    isSubpixelText = false
                    flags = flags and android.graphics.Paint.SUBPIXEL_TEXT_FLAG.inv()
                    textAlign = android.graphics.Paint.Align.LEFT
                    style = android.graphics.Paint.Style.FILL
                    typeface = face
                }
                val fm = p.fontMetrics
                val baseline = kotlin.math.round(((25f - (fm.bottom - fm.top)) / 2f - fm.top)).toFloat()
                val w = p.measureText(text)
                val sx = kotlin.math.round(((25f - w) / 2f)).toFloat().coerceAtLeast(0f)
                c.drawText(text, sx, baseline, p)
                val obj = com.nothing.ketchum.GlyphMatrixObject.Builder().setImageSource(target).setScale(100).setOrientation(0).setPosition(0, 0).setBrightness(255).build()
                val frame = com.nothing.ketchum.GlyphMatrixFrame.Builder().addTop(obj).build(context)
                safeSetFrame(glyphManager, frame)
                Log.d(TAG, "displaySmart(mixed, static) frame set")
            } else {
                Log.d(TAG, "displaySmart(mixed) -> scrolling, width=${off.contentWidth}")
                displayScrollingBitmap(off.bitmap, speedMs = 34L, loop = true) // requested 34 ms/pixel
            }
        }
    }

    private fun isJapaneseChar(ch: Char): Boolean {
        return (ch in '\u3040'..'\u30FF') || (ch in '\u31F0'..'\u31FF') || (ch in '\u3400'..'\u4DBF') || (ch in '\u4E00'..'\u9FFF') || (ch in '\uFF66'..'\uFF9D')
    }

    private fun pickThinnestTypeface(sampleText: String): Typeface {
        val candidates = listOf(
            Typeface.MONOSPACE,
            Typeface.SANS_SERIF,
            Typeface.DEFAULT,
            Typeface.create("sans-serif-light", Typeface.NORMAL),
            Typeface.create("sans-serif-thin", Typeface.NORMAL)
        )
        var best = Typeface.MONOSPACE
        var minCount = Int.MAX_VALUE
        candidates.forEach { face ->
            val bm = Bitmap.createBitmap(25, 25, Bitmap.Config.ARGB_8888)
            val c = Canvas(bm)
            val p = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 8f
                isAntiAlias = false
                isSubpixelText = false
                flags = flags and Paint.SUBPIXEL_TEXT_FLAG.inv()
                textAlign = Paint.Align.CENTER
                style = Paint.Style.FILL
                typeface = face
            }
            c.drawText(sampleText, 12.5f, 16f, p)
            val px = IntArray(25 * 25)
            bm.getPixels(px, 0, 25, 0, 0, 25, 25)
            val count = px.count { it == android.graphics.Color.WHITE }
            if (count in 1 until minCount) {
                minCount = count
                best = face
            }
        }
        return best
    }

    private fun renderCleanAsciiOffscreen(text: String, fontSize: Float = 9f): OffscreenRender {
        val paint = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = fontSize  // Use 8f for best MONOSPACE rendering
            isAntiAlias = false
            isSubpixelText = false
            flags = flags and Paint.SUBPIXEL_TEXT_FLAG.inv()
            textAlign = Paint.Align.LEFT
            style = Paint.Style.FILL
            isFilterBitmap = false
            isDither = false
            typeface = android.graphics.Typeface.MONOSPACE  // Clean and neutral
        }
        val textWidth = paint.measureText(text)
        val contentWidth = kotlin.math.ceil(textWidth).toInt()
        val padding = 8  // More padding for faster scrolling
        val offW = (contentWidth + padding * 2).coerceAtLeast(26)
        val bmp = Bitmap.createBitmap(offW, 25, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fm = paint.fontMetrics
        val baseline = kotlin.math.round(((25f - (fm.bottom - fm.top)) / 2f - fm.top)).toFloat()
        canvas.drawText(text, padding.toFloat(), baseline, paint)
        return OffscreenRender(bmp, contentWidth)
    }

    private fun renderAsciiOffscreen(text: String): OffscreenRender {
        val paint = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 8f
            isAntiAlias = false
            isSubpixelText = false
            flags = flags and Paint.SUBPIXEL_TEXT_FLAG.inv()
            textAlign = Paint.Align.LEFT
            style = Paint.Style.FILL
            isFilterBitmap = false
            isDither = false
            typeface = android.graphics.Typeface.SANS_SERIF
        }
        val textWidth = paint.measureText(text)
        val contentWidth = kotlin.math.ceil(textWidth).toInt()
        val padding = 4
        val offW = (contentWidth + padding * 2).coerceAtLeast(26)
        val bmp = Bitmap.createBitmap(offW, 25, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fm = paint.fontMetrics
        val baseline = kotlin.math.round(((25f - (fm.bottom - fm.top)) / 2f - fm.top)).toFloat()
        canvas.drawText(text, padding.toFloat(), baseline, paint)
        return OffscreenRender(bmp, contentWidth)
    }

    private fun renderMixedOffscreen(text: String): OffscreenRender {
        val paint = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 8f  // Same as clean ASCII for consistency
            isAntiAlias = false
            isSubpixelText = false
            flags = flags and Paint.SUBPIXEL_TEXT_FLAG.inv()
            textAlign = Paint.Align.LEFT
            style = Paint.Style.FILL
            isFilterBitmap = false
            isDither = false
        }
        // Measure total width per char using appropriate face/style
        var totalWidth = 0f
        for (ch in text) {
            paint.typeface = if (isJapaneseChar(ch)) {
                pickThinnestTypeface(ch.toString())
            } else {
                android.graphics.Typeface.MONOSPACE  // Use MONOSPACE for ASCII in mixed text too
            }
            totalWidth += paint.measureText(ch.toString())
        }
        val padding = 4
        val offW = kotlin.math.ceil(totalWidth + padding * 2).toInt().coerceAtLeast(26)
        val bmp = Bitmap.createBitmap(offW, 25, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fm = paint.fontMetrics
        val baseline = kotlin.math.round(((25f - (fm.bottom - fm.top)) / 2f - fm.top)).toFloat()
        var x = padding.toFloat()
        for (ch in text) {
            paint.typeface = if (isJapaneseChar(ch)) {
                pickThinnestTypeface(ch.toString())
            } else {
                android.graphics.Typeface.MONOSPACE  // Use MONOSPACE for ASCII in mixed text too
            }
            val w = paint.measureText(ch.toString())
            canvas.drawText(ch.toString(), kotlin.math.round(x).toFloat(), baseline, paint)
            x += w
        }
        return OffscreenRender(bmp, kotlin.math.ceil(totalWidth).toInt())
    }

    // (Removed) measureAsciiWidth: setText scrolling will use position-based animation

    private fun displayScrollingBitmap(offscreen: android.graphics.Bitmap, speedMs: Long, loop: Boolean) {
        Log.d(TAG, "displayScrollingBitmap: start width=${offscreen.width} speedMs=$speedMs loop=$loop")
        ensureReady { glyphManager ->
            stopScrolling()
            clearDisplay()
            val width = offscreen.width
            // Reuse a single frame bitmap to avoid per-frame allocations
            val frameBitmap = android.graphics.Bitmap.createBitmap(25, 25, android.graphics.Bitmap.Config.ARGB_8888)
            val frameCanvas = android.graphics.Canvas(frameBitmap)
            val src = android.graphics.Rect()
            val dst = android.graphics.Rect(0, 0, 25, 25)
            val thread = Thread {
                try {
                    try { Thread.currentThread().priority = Thread.NORM_PRIORITY + 2 } catch (_: Throwable) {}
                    do {
                        var cx = 0
                        while (true) {
                            if (stopRequested) { Log.d(TAG, "displayScrollingBitmap: stopRequested -> end thread"); return@Thread }

                            // Clear previous content to avoid ghosting before drawing new slice(s)
                            frameCanvas.drawColor(android.graphics.Color.BLACK)

                            if (cx <= width - 25) {
                                src.set(cx, 0, cx + 25, 25)
                                dst.set(0, 0, 25, 25)
                                frameCanvas.drawBitmap(offscreen, src, dst, null)
                            } else {
                                val rightWidth = width - cx
                                if (rightWidth > 0) {
                                    src.set(cx, 0, width, 25)
                                    dst.set(0, 0, rightWidth, 25)
                                    frameCanvas.drawBitmap(offscreen, src, dst, null)
                                }
                                val leftWidth = 25 - rightWidth
                                if (leftWidth > 0) {
                                    src.set(0, 0, leftWidth, 25)
                                    dst.set(rightWidth, 0, 25, 25)
                                    frameCanvas.drawBitmap(offscreen, src, dst, null)
                                }
                                // Restore dst for next iteration
                                dst.set(0, 0, 25, 25)
                            }
                            val obj = GlyphMatrixObject.Builder()
                                .setImageSource(frameBitmap)
                                .setScale(100)
                                .setOrientation(0)
                                .setPosition(0, 0)
                                .setBrightness(255)
                                .build()
                            val frame = com.nothing.ketchum.GlyphMatrixFrame.Builder().addTop(obj).build(context)
                            safeSetFrame(glyphManager, frame)
                            Thread.sleep(speedMs)
                            cx = (cx + 1) % width
                        }
                    } while (loop && !stopRequested)
                } catch (_: InterruptedException) { Log.d(TAG, "displayScrollingBitmap: interrupted") } catch (e: Exception) { Log.e(TAG, "displayScrollingBitmap: error ${e.message}") }
            }
            synchronized(scrollLock) {
                stopRequested = false
                thread.isDaemon = true
                scrollingThread = thread
                thread.start()
            }
        }
    }

    private fun renderTextToBitmap(glyphManager: com.nothing.ketchum.GlyphMatrixManager, text: String) {
        try {
            fun loadTypefaceFromAssets(vararg names: String): Typeface? {
                val am = context.assets
                // Try explicit names first
                for (name in names) {
                    runCatching { am.open("fonts/$name").close() }.onSuccess {
                        return Typeface.createFromAsset(am, "fonts/$name")
                    }
                }
                // Fallback: scan fonts directory and match candidates
                runCatching { am.list("fonts")?.toList() }.getOrNull()
                    ?.firstOrNull { file ->
                        val lower = file.lowercase()
                        (lower.contains("ntype") && lower.contains("jp")) || lower.contains("ntypejpregular")
                    }?.let { file ->
                        return runCatching { Typeface.createFromAsset(am, "fonts/$file") }.getOrNull()
                    }
                return null
            }
            val ntype: Typeface? = (loadTypefaceFromAssets(
                "Ntype-JP.ttf",
                "NTypeJP.ttf",
                "NTypeJP-Regular.ttf",
                "NTypeJP-Medium.ttf",
                "ntype_jp.ttf",
                "ntypejpregular.ttf",
                "NTypeJP.otf"
            ) ?: runCatching { Typeface.create("ntypejpregular", Typeface.NORMAL) }.getOrNull())
            // Try multiple font configurations for thinnest possible Japanese
            val fonts = listOfNotNull(
                // Try common fonts; we will pick the thinnest by pixel count
                android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL),
                android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL),
                // Optional: UI schoolbook font as candidate
                runCatching { android.graphics.Typeface.createFromAsset(context.assets, "fonts/UDDigiKyokashoN-R.ttf") }.getOrNull(),
                // Optional external jp font if loaded, but only as candidate
                ntype
            )


            if (ntype != null) {
                val bmp = Bitmap.createBitmap(25, 25, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val p = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 8f
                    isAntiAlias = false
                    isSubpixelText = false
                    flags = flags and Paint.SUBPIXEL_TEXT_FLAG.inv()
                    isFilterBitmap = false
                    isDither = false
                    // Avoid any artificial shaping that could thicken strokes
                    letterSpacing = 0f
                    textScaleX = 1f
                    isFakeBoldText = false
                    textAlign = Paint.Align.LEFT
                    style = Paint.Style.FILL
                    typeface = ntype
                }
                val fm = p.fontMetrics
                val baseline = kotlin.math.round(((25f - (fm.bottom - fm.top)) / 2f - fm.top)).toFloat()
                val w = p.measureText(text)
                val sx = kotlin.math.round(((25f - w) / 2f)).toFloat().coerceAtLeast(0f)
                c.drawText(text, sx, baseline, p)
                val textObject = GlyphMatrixObject.Builder()
                    .setImageSource(bmp)
                    .setScale(100)
                    .setOrientation(0)
                    .setPosition(0, 0)
                    .setBrightness(255)
                    .build()
                val frame = com.nothing.ketchum.GlyphMatrixFrame.Builder().addTop(textObject).build(context)
                safeSetFrame(glyphManager, frame)
                Log.d(TAG, "renderTextToBitmap: Ntype-JP font applied")
                toast("\u2713 Ntype-JP font applied")
                return
            }
            // Else: try each font and pick the thinnest result
            var bestBitmap: Bitmap? = null
            var minPixelCount = Int.MAX_VALUE

            for (font in fonts) {
                val testBitmap = Bitmap.createBitmap(25, 25, Bitmap.Config.ARGB_8888)
                val testCanvas = Canvas(testBitmap)

                val paint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 8f  // readability
                    isAntiAlias = false  // Sharp pixels
                    isSubpixelText = false
                    // Ensure no subpixel flag remains
                    flags = flags and Paint.SUBPIXEL_TEXT_FLAG.inv()
                    textAlign = Paint.Align.LEFT
                    style = Paint.Style.FILL
                    // Try UI font for kana normalization
                    typeface = font
                }
                // Center vertically using font metrics (rounded to integer pixels)
                val fm = paint.fontMetrics
                val baseline = ((25f - (fm.bottom - fm.top)) / 2f - fm.top)
                val baselineInt = kotlin.math.round(baseline).toFloat()
                // Center horizontally using measured width, aligned to integer pixel
                val textWidth = paint.measureText(text)
                val startX = kotlin.math.round(((25f - textWidth) / 2f)).toFloat().coerceAtLeast(0f)
                testCanvas.drawText(text, startX, baselineInt, paint)

                // Count white pixels to find thinnest rendering
                val pixels = IntArray(25 * 25)
                testBitmap.getPixels(pixels, 0, 25, 0, 0, 25, 25)
                val whitePixelCount = pixels.count { it == android.graphics.Color.WHITE }

                if (whitePixelCount > 0 && whitePixelCount < minPixelCount) {
                    minPixelCount = whitePixelCount
                    bestBitmap = testBitmap
                }
            }

            // Use the thinnest font result, or fallback to default
            val finalBitmap = bestBitmap ?: run {
                val fallbackBitmap = Bitmap.createBitmap(25, 25, Bitmap.Config.ARGB_8888)
                val fallbackCanvas = Canvas(fallbackBitmap)
                val fallbackPaint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 8f
                    isAntiAlias = false
                    isSubpixelText = false
                    flags = flags and Paint.SUBPIXEL_TEXT_FLAG.inv()
                    textAlign = Paint.Align.LEFT
                    style = Paint.Style.FILL
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                val fm2 = fallbackPaint.fontMetrics
                val baseline2 = ((25f - (fm2.bottom - fm2.top)) / 2f - fm2.top)
                val baseline2Int = kotlin.math.round(baseline2).toFloat()
                val w = fallbackPaint.measureText(text)
                val startX2 = kotlin.math.round(((25f - w) / 2f)).toFloat().coerceAtLeast(0f)
                fallbackCanvas.drawText(text, startX2, baseline2Int, fallbackPaint)
                fallbackBitmap
            }

            // Japanese text: use the optimized thinnest bitmap
            val textObject = GlyphMatrixObject.Builder()
                .setImageSource(finalBitmap)
                .setScale(100)  // Normal scale
                .setOrientation(0)
                .setPosition(0, 0)
                .setBrightness(255)
                .build()

            // Default frame
            val frame = com.nothing.ketchum.GlyphMatrixFrame.Builder()
                .addTop(textObject)
                .build(context)

            safeSetFrame(glyphManager, frame)
            Log.d(TAG, "renderTextToBitmap: thinnest bitmap used, pixels=$minPixelCount")
            toast("\u2713 Thinnest Japanese bitmap ($minPixelCount pixels): $text")
        } catch (e: Exception) {
            Log.e(TAG, "renderTextToBitmap failed: ${e.message}")
            toast("Text bitmap render failed: ${e.message}")
        }
    }

    // Stop any scrolling thread safely
    private fun stopScrolling() {
        synchronized(scrollLock) {
            stopRequested = true
            scrollingThread?.interrupt()
            scrollingThread = null
        }
    }

    fun startKeepAlive(periodMs: Long = 2500L) {
        synchronized(this) {
            if (keepAliveThread?.isAlive == true) { Log.d(TAG, "startKeepAlive: already running"); return }
            val t = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        // small nudge frame
                        runCatching { safeSetFrame(manager, com.nothing.ketchum.GlyphMatrixFrame.Builder().build(context)) }
                        try { Thread.sleep(25) } catch (_: Throwable) {}
                        try { Thread.sleep(periodMs) } catch (_: InterruptedException) { Log.d(TAG, "startKeepAlive: interrupted"); return@Thread }
                    }
                } catch (e: Exception) { Log.e(TAG, "startKeepAlive: error ${e.message}") }
            }
            keepAliveThread = t
            t.isDaemon = true
            t.start()
            Log.d(TAG, "startKeepAlive: started periodMs=$periodMs")
        }
    }

    fun stopKeepAlive() {
        Log.d(TAG, "stopKeepAlive: stopping thread")
        synchronized(this) {
            keepAliveThread?.interrupt()
            keepAliveThread = null
        }
    }

    fun stopDisplay() {
        Log.d(TAG, "stopDisplay: stop scrolling + blank frame")
        stopScrolling()
        needsWakeAfterStop = true
        runCatching { safeSetFrame(manager, com.nothing.ketchum.GlyphMatrixFrame.Builder().build(context)) }
            .onFailure { Log.e(TAG, "stopDisplay blank failed: ${it.message}") }
    }

    fun release() {
        Log.d(TAG, "release: stop everything and blank")
        stopKeepAlive()
        stopScrolling()
        runCatching { safeSetFrame(manager, com.nothing.ketchum.GlyphMatrixFrame.Builder().build(context)) }
            .onFailure { Log.e(TAG, "release blank failed: ${it.message}") }
    }

    private fun toast(msg: String) { /* silenced */ }

    suspend fun waitUntilReady(timeoutMs: Long = 2000L): Boolean {
        Log.d(TAG, "waitUntilReady: start timeoutMs=$timeoutMs")
        val ok = withTimeoutOrNull<Boolean>(timeoutMs) {
            while (!isRegistered) {
                // Proactively init and try to register until success
                kotlin.runCatching { manager.init(callback) }
                tryRegisterAndFlush()
                delay(60)
            }
            if (!isInitialized) {
                Log.w(TAG, "waitUntilReady: registered but init flag false; assuming initialized")
                isInitialized = true
            }
            true
        } ?: false
        Log.d(TAG, "waitUntilReady: result=$ok (init=$isInitialized reg=$isRegistered)")
        return ok
    }

    fun showHoldPixel(durationMs: Long = 120L) {
        Log.d(TAG, "showHoldPixel: durationMs=$durationMs")
        ensureReady { glyphManager ->
            val bmp = android.graphics.Bitmap.createBitmap(25, 25, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.setPixel(12, 12, android.graphics.Color.WHITE)
            val obj = com.nothing.ketchum.GlyphMatrixObject.Builder()
                .setImageSource(bmp)
                .setScale(100)
                .setOrientation(0)
                .setPosition(0, 0)
                .setBrightness(255)
                .build()
            val frame = com.nothing.ketchum.GlyphMatrixFrame.Builder().addTop(obj).build(context)
            safeSetFrame(glyphManager, frame)
            try { Thread.sleep(durationMs) } catch (_: Throwable) {}
        }
    }
}