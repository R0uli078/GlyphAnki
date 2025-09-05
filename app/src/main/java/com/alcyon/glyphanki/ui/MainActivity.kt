@file:Suppress("DirectSystemCurrentTimeMillisUsage")
package com.alcyon.glyphanki.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import com.alcyon.glyphanki.R
import androidx.lifecycle.lifecycleScope
import com.alcyon.glyphanki.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.alcyon.glyphanki.anki.AnkiRepository
import com.alcyon.glyphanki.service.ReviewForegroundService
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import android.widget.Button
import com.alcyon.glyphanki.anki.AnkiAccessManager
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import com.alcyon.glyphanki.glyph.GlyphController
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.text.SpannableString
import android.text.Spannable
import com.alcyon.glyphanki.ui.CustomTypefaceSpan
import android.view.Menu
import android.view.MenuItem
import com.alcyon.glyphanki.prefs.FieldPreferences
import android.widget.ImageButton
// Permissions helpers
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.view.WindowManager
import com.alcyon.glyphanki.session.ReviewSessionState

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val globalController by lazy { GlyphController(this) }
    private val fieldPrefs by lazy { FieldPreferences(this) }
    private lateinit var debugText: TextView

    // Runtime permission launcher for media read
    private var onMediaPermGranted: (() -> Unit)? = null
    private val mediaPermLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.all { it }
        if (granted) {
            onMediaPermGranted?.invoke()
        } else {
            Toast.makeText(this, "Media permission denied — audio playback may fail.", Toast.LENGTH_LONG).show()
        }
        onMediaPermGranted = null
    }

    // Only three UI messages, dedup consecutive
    private enum class UiMsg { STARTED, CUSTOM, STOPPED }
    private var lastUiMsg: UiMsg? = null
    private fun showUiMsg(type: UiMsg, message: String) {
        if (lastUiMsg == type) return
        debugText.text = message + "\n"
        lastUiMsg = type
    }

    // Wake Glyph once after app launch to avoid first-display hiccup
    private var glyphWarmed = false
    private fun warmGlyphIfNeeded() {
        if (glyphWarmed) return
        glyphWarmed = true
        lifecycleScope.launch {
            try { globalController.showHoldPixel(80) } catch (_: Throwable) {}
            // Keep-alive pulses for a few seconds after launch to avoid first-frame drop
            try { globalController.startKeepAlive(2500L) } catch (_: Throwable) {}
            try { delay(5000) } catch (_: Throwable) {}
            try { globalController.stopKeepAlive() } catch (_: Throwable) {}
        }
    }

    private var dimApplied = false
    private var originalWindowBrightness: Float? = null

    private fun applyWindowDimIfEnabled() {
        val enabled = getSharedPreferences("display_prefs", MODE_PRIVATE)
            .getBoolean("dim_screen_during_session", true)
        if (!enabled || dimApplied) return
        try {
            val w = window
            if (originalWindowBrightness == null) originalWindowBrightness = w.attributes.screenBrightness
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val lp = w.attributes
            lp.screenBrightness = 0.01f
            w.attributes = lp
            dimApplied = true
        } catch (_: Throwable) {}
    }

    private fun clearWindowDim() {
        if (!dimApplied) return
        try {
            val w = window
            val lp = w.attributes
            lp.screenBrightness = originalWindowBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            w.attributes = lp
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Throwable) {}
        originalWindowBrightness = null
        dimApplied = false
    }

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == com.alcyon.glyphanki.service.ReviewForegroundService.BROADCAST_ACTION) {
                val status = intent.getStringExtra(com.alcyon.glyphanki.service.ReviewForegroundService.EXTRA_STATUS)
                when (status) {
                    com.alcyon.glyphanki.service.ReviewForegroundService.STATUS_STARTED -> {
                        showUiMsg(UiMsg.STARTED, "Review session started")
                        applyWindowDimIfEnabled()
                    }
                    com.alcyon.glyphanki.service.ReviewForegroundService.STATUS_STOPPED -> {
                        showUiMsg(UiMsg.STOPPED, "Service stopped")
                        clearWindowDim()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            serviceStatusReceiver,
            IntentFilter(com.alcyon.glyphanki.service.ReviewForegroundService.BROADCAST_ACTION)
        )
        warmGlyphIfNeeded()
        // If a session is already active when returning to the activity, re-apply dim
        if (ReviewSessionState.active) {
            applyWindowDimIfEnabled()
        }
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceStatusReceiver)
        // When leaving the activity, clear brightness override to avoid leaking it if session stops later.
        clearWindowDim()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            // Ask user to disable battery optimizations if needed
            runCatching { maybeRequestIgnoreBatteryOptimizations() }
            // Removed in-screen accessibility suggestion; handled in Settings screen
        } catch (e: Exception) {
            Toast.makeText(this, "UI init error: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        // Quick glyph test on long press
        findViewById<TextView>(R.id.title).setOnLongClickListener {
            lifecycleScope.launch {
                try {
                    globalController.displaySmart("Test Glyph")
                    Toast.makeText(this@MainActivity, "Glyph test sent", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Glyph error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        
        // Triple tap to force refresh AnkiDroid cache
        findViewById<TextView>(R.id.title).apply {
            var clickCount = 0
            var lastClickTime = 0L
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastClickTime > 1500) {
                    clickCount = 1
                } else {
                    clickCount++
                }
                lastClickTime = now
                
                when (clickCount) {
                    2 -> {
                        if (now - lastClickTime < 1000) {
                            // Double tap - test AnkiDroid
                            testAnkiConnection()
                        }
                    }
                    3 -> {
                        // Triple tap - force refresh
                        Toast.makeText(this@MainActivity, "Forcing AnkiDroid cache refresh...", Toast.LENGTH_SHORT).show()
                        forceAnkiRefresh()
                        clickCount = 0
                    }
                }
            }
        }

        val deckSpinner = findViewById<Spinner>(R.id.deckSpinner)
        debugText = findViewById(R.id.debugText)
        val titleView = findViewById<TextView>(R.id.title)
        val stopBtn = findViewById<Button>(R.id.stopGlyphButton)
        val settingsBtn = findViewById<ImageButton>(R.id.settingsButton)

        // Rename the button text in UI to English
        stopBtn.text = getString(R.string.stop_service_button_label)

        // Try to apply Nothing fonts if present
        fun loadTypeface(vararg names: String): Typeface? {
            for (n in names) {
                runCatching { assets.open("fonts/$n").close() }.onSuccess {
                    return Typeface.createFromAsset(assets, "fonts/$n")
                }
            }
            // scan fonts dir
            val files = runCatching { assets.list("fonts")?.toList() }.getOrNull().orEmpty()
            files.firstOrNull { it.contains("NType82", ignoreCase = true) }?.let {
                return Typeface.createFromAsset(assets, "fonts/$it")
            }
            return null
        }
        fun loadTypefaceJapanese(): Typeface? {
            // prefer exact known files then scan
            val exact = loadTypeface(
                "Ntype-JP.ttf", "NTypeJP.ttf", "NTypeJP-Regular.ttf", "NTypeJP.otf", "ntype_jp.ttf", "ntypejpregular.ttf", "ntypejpregular.otf"
            )
            if (exact != null) return exact
            val files = runCatching { assets.list("fonts")?.toList() }.getOrNull().orEmpty()
            files.firstOrNull { f ->
                val l = f.lowercase()
                (l.contains("ntype") && l.contains("jp")) || l.contains("ntypejpregular")
            }?.let { return Typeface.createFromAsset(assets, "fonts/$it") }
            return runCatching { Typeface.create("ntypejpregular", Typeface.NORMAL) }.getOrNull()
        }
        // Use only UDDigiKyokashoN-R for UI (latin & japanese). Keep Ndot for title.
        val uiTypeface = loadTypeface("UDDigiKyokashoN-R.ttf")
        val ndot = loadTypeface("Ndot-57.otf", "Ndot-57.ttf")
        val jpTypeface = uiTypeface
        uiTypeface?.let { titleView.typeface = it }
        // Title should be Ndot if available
        ndot?.let { titleView.typeface = it }
        // Force Ndot by span to avoid later overrides
        ndot?.let {
            val s = SpannableString(titleView.text)
            s.setSpan(CustomTypefaceSpan(it), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            titleView.text = s
        }

        lifecycleScope.launch {
            try {
                val access = com.alcyon.glyphanki.anki.AnkiAccessManager(this@MainActivity)
                val decks = withContext(Dispatchers.IO) { com.alcyon.glyphanki.anki.AnkiRepository(this@MainActivity).listDecks() }
                if (decks.isEmpty()) {
                    access.openPermissionRequest(this@MainActivity)
                }
                val labels: List<String> = if (decks.isEmpty()) listOf("No deck found (check AnkiDroid API)") else decks.map { "${it.second}" }
                val adapter = object : ArrayAdapter<String>(this@MainActivity, R.layout.spinner_item_dark, labels) {
                    override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                        val v = super.getView(position, convertView, parent) as TextView
                        v.typeface = uiTypeface ?: v.typeface
                        // If item contains Japanese chars, prefer ntypejpregular if present
                        val text = v.text?.toString().orEmpty()
                        if (text.any { it.code > 0x3000 }) {
                            jpTypeface?.let { v.typeface = it }
                        }
                        return v
                    }
                    override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                        val v = super.getDropDownView(position, convertView, parent) as TextView
                        v.typeface = uiTypeface ?: v.typeface
                        val text = v.text?.toString().orEmpty()
                        if (text.any { it.code > 0x3000 }) {
                            jpTypeface?.let { v.typeface = it }
                        }
                        return v
                    }
                }
                adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark)
                deckSpinner.adapter = adapter

            } catch (e: Exception) {
                debugText.append("Init decks exception: ${e.message}\n")
            }
        }

        val startBtn = findViewById<Button>(R.id.startButton)
        uiTypeface?.let { face -> startBtn.typeface = face }
        startBtn.setOnClickListener {
            // Ensure media read permission before starting session (needed for /storage/emulated/0/AnkiDroid/collection.media)
            if (!hasMediaReadPermission()) {
                onMediaPermGranted = { startSessionFromUi(deckSpinner) }
                requestMediaReadPermission()
                return@setOnClickListener
            }
            startSessionFromUi(deckSpinner)
        }

        // Settings gear opens SettingsActivity explicitly
        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Using fixed device code 24111 (Nothing Phone 3)

        // Global controller for better switching

        val sendCustom = findViewById<Button>(R.id.sendCustomTextButton)
        val customInput = findViewById<EditText>(R.id.customTextInput)

        // Apply UI font everywhere (UDDigiKyokashoN-R), Ndot for title already applied
        uiTypeface?.let { face ->
            sendCustom.typeface = face
            startBtn.typeface = face
            stopBtn.typeface = face
            customInput.typeface = face
        }

        // Also traverse view tree to apply fonts broadly
        fun applyFontsRecursively(view: View) {
            when (view) {
                is TextView -> {
                    if (view.id == R.id.title) return // keep Ndot on title
                    val txt = view.text
                    val content = txt?.toString().orEmpty()
                    if (content.isNotEmpty() && (uiTypeface != null)) {
                        val sp = SpannableString(content)
                        sp.setSpan(CustomTypefaceSpan(uiTypeface), 0, sp.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        view.text = sp
                    } else {
                        uiTypeface?.let { view.typeface = it }
                    }
                }
                is ViewGroup -> {
                    for (i in 0 until view.childCount) applyFontsRecursively(view.getChildAt(i))
                }
            }
        }
        applyFontsRecursively(window.decorView.rootView)

        sendCustom.setOnClickListener {
            val text = customInput.text?.toString().orEmpty()
            if (text.isBlank()) {
                Toast.makeText(this, "Enter text", Toast.LENGTH_SHORT).show()
            } else {
                // Decide based on measured width rather than char count
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 8f
                    isAntiAlias = false
                    textAlign = android.graphics.Paint.Align.LEFT
                    style = android.graphics.Paint.Style.FILL
                    typeface = uiTypeface
                }
                val width = paint.measureText(text)
                if (width > 25f) {
                    // Mixed rendering with smart scroll when needed
                    globalController.displaySmart(text)
                } else {
                    // Mixed rendering centered (bitmap)
                    globalController.displaySmart(text)
                }
                showUiMsg(UiMsg.CUSTOM, "Custom text displayed")
            }
        }

        findViewById<Button>(R.id.stopGlyphButton).setOnClickListener {
            // Stop current glyph display immediately
            globalController.stopDisplay()
            // Stop the running review session cleanly via action
            val stopIntent = Intent(this, com.alcyon.glyphanki.service.ReviewForegroundService::class.java).apply {
                putExtra("action", com.alcyon.glyphanki.service.ReviewForegroundService.ACTION_STOP)
            }
            runCatching { startService(stopIntent) }
        }
    }

    private fun startSessionFromUi(deckSpinner: Spinner) {
        // Before starting a new session, send a STOP to avoid duplicates
        val stopIntent = Intent(this, com.alcyon.glyphanki.service.ReviewForegroundService::class.java).apply {
            putExtra("action", com.alcyon.glyphanki.service.ReviewForegroundService.ACTION_STOP)
        }
        runCatching { startService(stopIntent) }
        // Delay a bit to let service tear down
        lifecycleScope.launch {
            delay(150)
            // If still empty, prompt explicit permission request in AnkiDroid
            val existing = com.alcyon.glyphanki.anki.AnkiRepository(this@MainActivity).listDecks()
            if (existing.isEmpty()) {
                com.alcyon.glyphanki.anki.AnkiAccessManager(this@MainActivity).openPermissionRequest(this@MainActivity)
                return@launch
            }
            val decks2 = com.alcyon.glyphanki.anki.AnkiRepository(this@MainActivity).listDecks()
            if (decks2.isNotEmpty()) {
                val position = deckSpinner.selectedItemPosition.coerceIn(0, decks2.lastIndex)
                val (deckId, _) = decks2[position]
                // Get field preferences
                val fronts = fieldPrefs.getFrontFields()
                val backs = fieldPrefs.getBackFields()
                val frontFieldName = fronts.firstOrNull() ?: "Front"
                val backFieldName = backs.firstOrNull() ?: "Back"
                // Start the review service
                com.alcyon.glyphanki.service.ReviewForegroundService.startSessionForDeck(
                    this@MainActivity,
                    deckId,
                    frontFieldName,
                    backFieldName,
                    fronts,
                    backs,
                    enableBackAutoAdvance = false
                )
                // Only one start message via broadcast
            }
        }
    }

    private fun hasMediaReadPermission(): Boolean {
        val perms = requiredMediaPermissions()
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestMediaReadPermission() {
        val perms = requiredMediaPermissions()
        mediaPermLauncher.launch(perms)
    }

    private fun requiredMediaPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Best-effort release of glyph service when leaving screen
        runCatching {
            globalController.release()
        }
    }

    override fun onResume() {
        super.onResume()
        // Only reinitialize if needed, not on every resume to avoid crashes
        // The controller will handle reconnection automatically when needed
    }

    private fun maybeRequestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            val pkg = packageName
            val ignoring = pm?.isIgnoringBatteryOptimizations(pkg) == true
            if (!ignoring) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$pkg")
                startActivity(intent)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Settings").setIcon(android.R.drawable.ic_menu_preferences).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }
    
    private fun testAnkiConnection() {
        lifecycleScope.launch {
            try {
                val repo = AnkiRepository(this@MainActivity)
                                val deckPairs = repo.listDecks()
                val deckInfo = deckPairs.take(3).joinToString(", ") { "${it.second} (${it.first})" }
                Toast.makeText(this@MainActivity, "AnkiDroid OK: $deckInfo", Toast.LENGTH_LONG).show()
                
                if (deckPairs.isNotEmpty()) {
                    val firstDeckId = deckPairs.first().first
                    
                    // Test force sync
                    repo.forceSyncRefresh()
                    
                    val cards = repo.loadReviewCards(firstDeckId)
                    val message = "Cards: ${cards.size} loaded from deck ${deckPairs.first().second}"
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    
                    // Show details about first few cards
                    if (cards.isNotEmpty()) {
                        val details = cards.take(2).joinToString("; ") { 
                            "Note${it.noteId}:${it.cardOrd}" 
                        }
                        Toast.makeText(this@MainActivity, "Sample: $details", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "AnkiDroid Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun forceAnkiRefresh() {
        lifecycleScope.launch {
            try {
                // Try to force AnkiDroid to refresh by making several quick queries
                val repo = AnkiRepository(this@MainActivity)
                val decks = repo.listDecks()
                Toast.makeText(this@MainActivity, "Found ${decks.size} decks after refresh", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Refresh failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}


