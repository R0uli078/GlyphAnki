package com.alcyon.glyphanki.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.style.StyleSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import com.alcyon.glyphanki.R
import com.alcyon.glyphanki.prefs.FieldPreferences
import com.alcyon.glyphanki.prefs.MediaPreferences
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.DocumentsContract
import android.graphics.Typeface
import com.alcyon.glyphanki.anki.AnkiAccessManager
import android.database.DataSetObserver
import android.view.LayoutInflater
import android.content.SharedPreferences
import androidx.core.content.ContextCompat

class SettingsActivity : ComponentActivity() {
    private var udFace: Typeface? = null
    private lateinit var frontAdapter: ArrayAdapter<String>
    private lateinit var backAdapter: ArrayAdapter<String>
    private lateinit var frontAudioAdapter: ArrayAdapter<String>
    private lateinit var backAudioAdapter: ArrayAdapter<String>

    private lateinit var safStatusView: TextView
    private lateinit var audioSection: View
    private lateinit var audioToggle: Switch
    private lateinit var accessibilityStatus: TextView
    private lateinit var ankiApiStatus: TextView

    private lateinit var displayPrefs: SharedPreferences

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        handlePickedTreeUri(uri)
    }

    private fun handlePickedTreeUri(uri: Uri?) {
        if (uri == null) return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
        MediaPreferences(this).setMediaTreeUri(uri)
        updateSafStatus()
        // Inform the user; the system picker may remain until user dismisses it
        Toast.makeText(this, "Media folder granted", Toast.LENGTH_SHORT).show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        // Robust check via Settings.Secure
        val enabled = try { Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) == 1 } catch (_: Exception) { false }
        if (!enabled) return false
        val services = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val target = "$packageName/com.alcyon.glyphanki.accessibility.VolumeAccessibilityService"
        return services.split(':').any { it.equals(target, ignoreCase = true) }
    }

    private fun applyGlobalTypeface(root: View) {
        // Apply UDDigiKyokashoN-R to all text widgets except the title, preserving existing style (e.g., italic)
        val ud = runCatching { Typeface.createFromAsset(assets, "fonts/UDDigiKyokashoN-R.ttf") }.getOrNull() ?: return
        fun apply(v: View) {
            when (v) {
                is TextView -> if (v.id != R.id.settingsTitle) v.setTypeface(ud, v.typeface?.style ?: Typeface.NORMAL)
                is ViewGroup -> for (i in 0 until v.childCount) apply(v.getChildAt(i))
            }
        }
        apply(root)
    }

    private fun styleHintItalicSmall(editText: EditText) {
        // Hint text size and style are controlled by TextView defaults; set smaller + italic via spans
        val hint = editText.hint?.toString() ?: return
        val sp = SpannableString(hint)
        sp.setSpan(StyleSpan(Typeface.ITALIC), 0, sp.length, 0)
        sp.setSpan(RelativeSizeSpan(0.9f), 0, sp.length, 0)
        editText.hint = sp
    }

    private fun updateAccessibilityStatus() {
        val enabled = isAccessibilityEnabled()
        accessibilityStatus.text = if (enabled) "Volume Shortcut: configured" else "Volume Shortcut: not configured"
        accessibilityStatus.setTextColor(if (enabled) 0xFF2ECC71.toInt() else 0xFFFF3B30.toInt())
    }

    private fun isAnkiApiGranted(): Boolean {
        // Quick probe by attempting to query a lightweight URI; fall back to package check only
        return try {
            packageManager.getPackageInfo("com.ichi2.anki", 0)
            true
        } catch (_: Exception) {
            try { packageManager.getPackageInfo("com.ichi2.anki.debug", 0); true } catch (_: Exception) { false }
        }
    }

    private fun updateAnkiApiStatus() {
        val granted = isAnkiApiGranted()
        ankiApiStatus.text = if (granted) "AnkiDroid API: configured" else "AnkiDroid API: not configured"
        ankiApiStatus.setTextColor(if (granted) 0xFF2ECC71.toInt() else 0xFFFF3B30.toInt())
    }

    private fun updateSafStatus() {
        val mp = MediaPreferences(this)
        val uri = mp.getMediaTreeUri()
        safStatusView.text = if (uri != null) "SAF: configured" else "SAF: not configured"
        safStatusView.setTextColor(if (uri != null) 0xFF2ECC71.toInt() else 0xFFFF3B30.toInt())
        val enabled = mp.isAudioEnabled()
        audioSection.visibility = if (enabled) View.VISIBLE else View.GONE
        audioToggle.isChecked = enabled
    }

    private fun ListView.setHeightToWrapContent() {
        val adapter = adapter ?: return
        var totalHeight = 0
        for (i in 0 until adapter.count) {
            val item = adapter.getView(i, null, this)
            item.measure(
                View.MeasureSpec.makeMeasureSpec(this.width.coerceAtLeast(1), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.UNSPECIFIED
            )
            totalHeight += item.measuredHeight
        }
        totalHeight += this.dividerHeight * ((adapter.count - 1).coerceAtLeast(0))
        val params = layoutParams
        params.height = if (adapter.count == 0) ViewGroup.LayoutParams.WRAP_CONTENT else totalHeight
        layoutParams = params
        requestLayout()
    }

    private fun ListView.setHeightToWrapContentNow() {
        val adapter = adapter ?: return
        var totalHeight = 0
        val widthSpec = View.MeasureSpec.makeMeasureSpec(this.measuredWidth.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
        for (i in 0 until adapter.count) {
            val item = adapter.getView(i, null, this)
            val lp = item.layoutParams
            if (lp == null) item.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            item.measure(widthSpec, View.MeasureSpec.UNSPECIFIED)
            totalHeight += item.measuredHeight
        }
        totalHeight += this.dividerHeight * ((adapter.count - 1).coerceAtLeast(0))
        val params = layoutParams
        params.height = if (adapter.count == 0) 0 else totalHeight
        layoutParams = params
        requestLayout()
    }

    private fun ListView.updateHeightToContent() { post { setHeightToWrapContentNow() } }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updateAnkiApiStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = FieldPreferences(this)
        prefs.ensureDefaults()

        displayPrefs = getSharedPreferences("display_prefs", MODE_PRIVATE)

        // Typeface handle for adapters
        udFace = runCatching { Typeface.createFromAsset(assets, "fonts/UDDigiKyokashoN-R.ttf") }.getOrNull()

        // Root for global font application
        val root = findViewById<ViewGroup>(android.R.id.content)

        val frontList = findViewById<ListView>(R.id.frontList)
        val backList = findViewById<ListView>(R.id.backList)
        val frontInput = findViewById<EditText>(R.id.frontInput)
        val backInput = findViewById<EditText>(R.id.backInput)
        val addFront = findViewById<Button>(R.id.addFront)
        val addBack = findViewById<Button>(R.id.addBack)

        val frontAudioList = findViewById<ListView>(R.id.frontAudioList)
        val backAudioList = findViewById<ListView>(R.id.backAudioList)
        val frontAudioInput = findViewById<EditText>(R.id.frontAudioInput)
        val backAudioInput = findViewById<EditText>(R.id.backAudioInput)
        val addFrontAudio = findViewById<Button>(R.id.addFrontAudio)
        val addBackAudio = findViewById<Button>(R.id.addBackAudio)
        val btnPickMediaFolder = findViewById<Button>(R.id.btnPickMediaFolder)
        val btnAccessibility = findViewById<Button>(R.id.btnEnableAccessibility)
        safStatusView = findViewById(R.id.safStatus)
        audioSection = findViewById(R.id.audioSection)
        audioToggle = findViewById(R.id.toggleAudio)
        accessibilityStatus = findViewById(R.id.accessibilityStatus)
        ankiApiStatus = findViewById(R.id.ankiApiStatus)
        val btnGrantAnkiApi = findViewById<Button>(R.id.btnGrantAnkiApi)
        val btnOpenAnki: Button? = findViewById(R.id.btnOpenAnki)

        val title = findViewById<TextView>(R.id.settingsTitle)
        title.text = "Setting"
        // Title uses Ndot
        runCatching { title.typeface = Typeface.createFromAsset(assets, "fonts/Ndot-57.otf") }

        // Apply UDDigiKyokashoN-R to the rest
        applyGlobalTypeface(root)

        // Style hints smaller + italic
        listOf(frontInput, backInput, frontAudioInput, backAudioInput).forEach { styleHintItalicSmall(it) }

        // Make only secondary Add buttons smaller; keep primary buttons large
        fun shrink(button: Button) {
            button.textSize = 12f
            val params = button.layoutParams
            params?.let {
                it.height = (32 * resources.displayMetrics.density).toInt()
                button.layoutParams = it
            }
        }
        listOf(addFront, addBack, addFrontAudio, addBackAudio).forEach { shrink(it) }

        val fronts = prefs.getFrontFields()
        val backs = prefs.getBackFields()
        val frontAudios = prefs.getFrontAudioFields()
        val backAudios = prefs.getBackAudioFields()

        fun save() {
            prefs.setFrontFields(fronts); prefs.setBackFields(backs)
            prefs.setFrontAudioFields(frontAudios); prefs.setBackAudioFields(backAudios)
        }

        frontAdapter = object : ArrayAdapter<String>(this, R.layout.item_field_row, R.id.fieldName, fronts) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                v.findViewById<TextView>(R.id.fieldName).apply {
                    text = fronts[position]
                    udFace?.let { typeface = it }
                }
                v.findViewById<ImageButton>(R.id.btnUp).setOnClickListener { if (position > 0) { val item = fronts.removeAt(position); fronts.add(position - 1, item); notifyDataSetChanged(); save() } }
                v.findViewById<ImageButton>(R.id.btnDown).setOnClickListener { if (position < fronts.lastIndex) { val item = fronts.removeAt(position); fronts.add(position + 1, item); notifyDataSetChanged(); save() } }
                v.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener { fronts.removeAt(position); notifyDataSetChanged(); save() }
                return v
            }
        }
        backAdapter = object : ArrayAdapter<String>(this, R.layout.item_field_row, R.id.fieldName, backs) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                v.findViewById<TextView>(R.id.fieldName).apply {
                    text = backs[position]
                    udFace?.let { typeface = it }
                }
                v.findViewById<ImageButton>(R.id.btnUp).setOnClickListener { if (position > 0) { val item = backs.removeAt(position); backs.add(position - 1, item); notifyDataSetChanged(); save() } }
                v.findViewById<ImageButton>(R.id.btnDown).setOnClickListener { if (position < backs.lastIndex) { val item = backs.removeAt(position); backs.add(position + 1, item); notifyDataSetChanged(); save() } }
                v.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener { backs.removeAt(position); notifyDataSetChanged(); save() }
                return v
            }
        }
        frontAudioAdapter = object : ArrayAdapter<String>(this, R.layout.item_field_row, R.id.fieldName, frontAudios) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                v.findViewById<TextView>(R.id.fieldName).apply {
                    text = frontAudios[position]
                    udFace?.let { typeface = it }
                }
                v.findViewById<ImageButton>(R.id.btnUp).setOnClickListener { if (position > 0) { val item = frontAudios.removeAt(position); frontAudios.add(position - 1, item); notifyDataSetChanged(); save() } }
                v.findViewById<ImageButton>(R.id.btnDown).setOnClickListener { if (position < frontAudios.lastIndex) { val item = frontAudios.removeAt(position); frontAudios.add(position + 1, item); notifyDataSetChanged(); save() } }
                v.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener { frontAudios.removeAt(position); notifyDataSetChanged(); save() }
                return v
            }
        }
        backAudioAdapter = object : ArrayAdapter<String>(this, R.layout.item_field_row, R.id.fieldName, backAudios) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                v.findViewById<TextView>(R.id.fieldName).apply {
                    text = backAudios[position]
                    udFace?.let { typeface = it }
                }
                v.findViewById<ImageButton>(R.id.btnUp).setOnClickListener { if (position > 0) { val item = backAudios.removeAt(position); backAudios.add(position - 1, item); notifyDataSetChanged(); save() } }
                v.findViewById<ImageButton>(R.id.btnDown).setOnClickListener { if (position < backAudios.lastIndex) { val item = backAudios.removeAt(position); backAudios.add(position + 1, item); notifyDataSetChanged(); save() } }
                v.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener { backAudios.removeAt(position); notifyDataSetChanged(); save() }
                return v
            }
        }

        frontList.adapter = frontAdapter
        backList.adapter = backAdapter
        frontAudioList.adapter = frontAudioAdapter
        backAudioList.adapter = backAudioAdapter

        // Wire up buttons
        btnAccessibility.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Toast.makeText(this, "Cannot open Accessibility settings", Toast.LENGTH_SHORT).show() }
        }
        btnGrantAnkiApi.setOnClickListener {
            AnkiAccessManager(this).openPermissionRequest(this)
        }
        btnOpenAnki?.setOnClickListener {
            val pkg = packageName
            val uid = applicationInfo.uid

            // Priorité: écrans Settings de l’app (App Permission Settings). Ajoute data=package: et variantes supplémentaires.
            val candidates = mutableListOf<Intent>().apply {
                // 1) AppPermissionSettings (direct)
                add(Intent().apply {
                    setClassName("com.android.settings", "com.android.settings.applications.appinfo.AppPermissionSettings")
                    data = Uri.parse("package:$pkg")
                    putExtra("android.intent.extra.PACKAGE_NAME", pkg)
                    putExtra("app_package", pkg)
                    putExtra("package_name", pkg)
                    putExtra("app_uid", uid)
                })
                // 2) Settings$AppPermissionSettingsActivity
                add(Intent().apply {
                    setClassName("com.android.settings", "com.android.settings.Settings\$AppPermissionSettingsActivity")
                    data = Uri.parse("package:$pkg")
                    putExtra("android.intent.extra.PACKAGE_NAME", pkg)
                    putExtra("app_package", pkg)
                    putExtra("package_name", pkg)
                    putExtra("app_uid", uid)
                })
                // 3) SubSettings avec fragment
                add(Intent().apply {
                    setClassName("com.android.settings", "com.android.settings.SubSettings")
                    data = Uri.parse("package:$pkg")
                    putExtra(":settings:show_fragment", "com.android.settings.applications.appinfo.AppPermissionSettings")
                    putExtra("android.intent.extra.PACKAGE_NAME", pkg)
                    putExtra("app_package", pkg)
                    putExtra("package_name", pkg)
                    putExtra("app_uid", uid)
                    putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                        putString("android.intent.extra.PACKAGE_NAME", pkg)
                        putString("app_package", pkg)
                        putString("package_name", pkg)
                        putInt("app_uid", uid)
                    })
                })
                // 4) Settings$AppInfoDashboardActivity + fragment (autre chemin OEM)
                add(Intent().apply {
                    setClassName("com.android.settings", "com.android.settings.Settings\$AppInfoDashboardActivity")
                    data = Uri.parse("package:$pkg")
                    putExtra(":settings:show_fragment", "com.android.settings.applications.appinfo.AppPermissionSettings")
                    putExtra("android.intent.extra.PACKAGE_NAME", pkg)
                    putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                        putString("android.intent.extra.PACKAGE_NAME", pkg)
                    })
                })
            }

            // Fallback: App Info
            candidates += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
            }

            var launched = false
            for (intent in candidates) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(packageManager) != null) {
                    try {
                        startActivity(intent)
                        launched = true
                        break
                    } catch (_: Throwable) { /* try next */ }
                }
            }
            if (!launched) {
                Toast.makeText(this, "Cannot open app settings", Toast.LENGTH_SHORT).show()
            } else {
                // Guidance for the user to grant the DB permission on OEM ROMs that land on App Info
                Toast.makeText(
                    this,
                    "Permissions → Additional permissions → Read and write AnkiDroid database",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        btnPickMediaFolder.setOnClickListener {
            val initial: Uri? = runCatching {
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:AnkiDroid/collection.media"
                )
            }.getOrNull()
            runCatching { openTree.launch(initial) }.onFailure { openTree.launch(null) }
        }

        // Inflate and insert display settings card
        val mainLayout = (root.getChildAt(0) as? ScrollView)?.getChildAt(0) as? LinearLayout
        val displayCard = LayoutInflater.from(this).inflate(R.layout.settings_display_card, mainLayout, false)
        // Append at the end to avoid disturbing existing blocks order
        mainLayout?.addView(displayCard)
        // Add a tiny bottom spacer at the very end for a clean end of the menu
        mainLayout?.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (16 * resources.displayMetrics.density).toInt()).apply {
                topMargin = (8 * resources.displayMetrics.density).toInt()
            }
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        })
        // Apply app-wide typeface to the newly inflated block (since it was added after initial pass)
        applyGlobalTypeface(displayCard)

        // Default values
        val defaultFontAscii = 10f
        val defaultFontBitmap = 12f
        val defaultScrollAscii = 24
        val defaultScrollBitmap = 34
        val minFont = 6f
        val maxFont = 18f
        val minScroll = 10
        val maxScroll = 60

        // Load or set defaults
        val fontAscii = displayPrefs.getFloat("font_ascii", defaultFontAscii)
        val fontBitmap = displayPrefs.getFloat("font_bitmap", defaultFontBitmap)
        val scrollAscii = displayPrefs.getInt("scroll_ascii", defaultScrollAscii)
        val scrollBitmap = displayPrefs.getInt("scroll_bitmap", defaultScrollBitmap)

        val seekFontAscii = displayCard.findViewById<SeekBar>(R.id.seekFontSizeAscii)
        val seekFontBitmap = displayCard.findViewById<SeekBar>(R.id.seekFontSizeBitmap)
        val seekScrollAscii = displayCard.findViewById<SeekBar>(R.id.seekScrollSpeedAscii)
        val seekScrollBitmap = displayCard.findViewById<SeekBar>(R.id.seekScrollSpeedBitmap)
        val valueFontAscii = displayCard.findViewById<TextView>(R.id.valueFontSizeAscii)
        val valueFontBitmap = displayCard.findViewById<TextView>(R.id.valueFontSizeBitmap)
        val valueScrollAscii = displayCard.findViewById<TextView>(R.id.valueScrollSpeedAscii)
        val valueScrollBitmap = displayCard.findViewById<TextView>(R.id.valueScrollSpeedBitmap)
        val markerFontAscii = displayCard.findViewById<View>(R.id.markerFontAscii)
        val markerFontBitmap = displayCard.findViewById<View>(R.id.markerFontBitmap)
        val markerScrollAscii = displayCard.findViewById<View>(R.id.markerScrollAscii)
        val markerScrollBitmap = displayCard.findViewById<View>(R.id.markerScrollBitmap)

        // Style seekbars programmatically for consistency
        listOf(seekFontAscii, seekFontBitmap, seekScrollAscii, seekScrollBitmap).forEach { sb ->
            sb.thumbTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.nothing_red))
            sb.progressTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.nothing_red))
            sb.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x14FFFFFF)
            sb.splitTrack = false
        }

        // Helper: round to integer for both font and speed
        fun roundFont(v: Float): Float = kotlin.math.round(v)
        fun roundSpeed(v: Int): Int = v // already integer; mapping uses Int

        // Set initial positions (middle = default)
        seekFontAscii.progress = (((fontAscii - minFont) / (maxFont - minFont)) * 100).toInt()
        seekFontBitmap.progress = (((fontBitmap - minFont) / (maxFont - minFont)) * 100).toInt()
        seekScrollAscii.progress = (((scrollAscii - minScroll).toFloat() / (maxScroll - minScroll)) * 100).toInt()
        seekScrollBitmap.progress = (((scrollBitmap - minScroll).toFloat() / (maxScroll - minScroll)) * 100).toInt()

        // Magnetic snap threshold around defaults (percent of track)
        fun snapProgress(progress: Int, defaultValue: Int, min: Int, max: Int): Int {
            val current = min + ((progress / 100f) * (max - min)).toInt()
            val snap = 2 // tighter: +/-2 units
            return if (kotlin.math.abs(current - defaultValue) <= snap) (((defaultValue - min).toFloat() / (max - min)) * 100).toInt() else progress
        }
        fun snapProgressF(progress: Int, defaultValue: Float, min: Float, max: Float, threshold: Float = 0.6f): Int {
            val current = min + (progress / 100f) * (max - min)
            return if (kotlin.math.abs(current - defaultValue) <= threshold) (((defaultValue - min) / (max - min)) * 100).toInt() else progress
        }
        fun snapProgressFMulti(progress: Int, snapValues: FloatArray, min: Float, max: Float, threshold: Float = 0.6f): Int {
            val current = min + (progress / 100f) * (max - min)
            var best: Float? = null
            var bestDiff = Float.MAX_VALUE
            for (s in snapValues) {
                val d = kotlin.math.abs(current - s)
                if (d < bestDiff) { bestDiff = d; best = s }
            }
            return if (best != null && bestDiff <= threshold) (((best!! - min) / (max - min)) * 100).toInt() else progress
        }

        fun updateFontAscii(progress: Int) {
            val raw = minFont + (progress / 100f) * (maxFont - minFont)
            val value = roundFont(raw)
            valueFontAscii.text = String.format("%.0f", value)
            displayPrefs.edit().putFloat("font_ascii", value).apply()
        }
        fun updateFontBitmap(progress: Int) {
            val raw = minFont + (progress / 100f) * (maxFont - minFont)
            val value = roundFont(raw)
            valueFontBitmap.text = String.format("%.0f", value)
            displayPrefs.edit().putFloat("font_bitmap", value).apply()
        }
        fun updateScrollAscii(progress: Int) {
            val value = minScroll + ((progress / 100f) * (maxScroll - minScroll)).toInt()
            valueScrollAscii.text = value.toString()
            displayPrefs.edit().putInt("scroll_ascii", value).apply()
        }
        fun updateScrollBitmap(progress: Int) {
            val value = minScroll + ((progress / 100f) * (maxScroll - minScroll)).toInt()
            valueScrollBitmap.text = value.toString()
            displayPrefs.edit().putInt("scroll_bitmap", value).apply()
        }

        // Initialize values
        updateFontAscii(seekFontAscii.progress)
        updateFontBitmap(seekFontBitmap.progress)
        updateScrollAscii(seekScrollAscii.progress)
        updateScrollBitmap(seekScrollBitmap.progress)

        // True magnetic snapping: update thumb to snapped position
        seekFontAscii.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val p = if (fromUser) snapProgressF(progress, defaultFontAscii, minFont, maxFont) else progress
                if (fromUser && p != progress) seekBar?.progress = p
                updateFontAscii(p)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        seekFontBitmap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val p = if (fromUser) snapProgressFMulti(progress, floatArrayOf(8f, 12f, 16f), minFont, maxFont) else progress
                if (fromUser && p != progress) seekBar?.progress = p
                updateFontBitmap(p)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        seekScrollAscii.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val p = if (fromUser) snapProgress(progress, defaultScrollAscii, minScroll, maxScroll) else progress
                if (fromUser && p != progress) seekBar?.progress = p
                updateScrollAscii(p)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        seekScrollBitmap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val p = if (fromUser) snapProgress(progress, defaultScrollBitmap, minScroll, maxScroll) else progress
                if (fromUser && p != progress) seekBar?.progress = p
                updateScrollBitmap(p)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Place red default markers exactly on the SeekBar track based on default values
        fun placeMarker(marker: View, seekBar: SeekBar, defaultValue: Float, min: Float, max: Float) {
            seekBar.post {
                val trackWidth = seekBar.width - seekBar.paddingStart - seekBar.paddingEnd
                if (trackWidth <= 0) return@post
                val ratio = ((defaultValue - min) / (max - min)).coerceIn(0f, 1f)
                val x = seekBar.paddingStart + ratio * trackWidth
                val lp = (marker.layoutParams as FrameLayout.LayoutParams)
                lp.leftMargin = (x - (marker.measuredWidth.takeIf { it > 0 } ?: marker.layoutParams.width) / 2f).toInt()
                marker.layoutParams = lp
                marker.requestLayout()
            }
        }
        fun placeMarkerInt(marker: View, seekBar: SeekBar, defaultValue: Int, min: Int, max: Int) {
            placeMarker(marker, seekBar, defaultValue.toFloat(), min.toFloat(), max.toFloat())
        }
        placeMarker(markerFontAscii, seekFontAscii, defaultFontAscii, minFont, maxFont)
        placeMarker(markerFontBitmap, seekFontBitmap, defaultFontBitmap, minFont, maxFont)
        placeMarkerInt(markerScrollAscii, seekScrollAscii, defaultScrollAscii, minScroll, maxScroll)
        placeMarkerInt(markerScrollBitmap, seekScrollBitmap, defaultScrollBitmap, minScroll, maxScroll)

        // Audio enable toggle behavior
        val mp = MediaPreferences(this)
        audioToggle.isChecked = mp.isAudioEnabled()
        audioSection.visibility = if (mp.isAudioEnabled()) View.VISIBLE else View.GONE
        // Make switch accent red via textColors on status text and themed drawable already in XML
        audioToggle.setOnCheckedChangeListener { _, isChecked ->
            mp.setAudioEnabled(isChecked)
            if (isChecked && mp.getMediaTreeUri() == null) {
                try {
                    val initial: Uri? = runCatching {
                        DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents",
                            "primary:AnkiDroid/collection.media"
                        )
                    }.getOrNull()
                    openTree.launch(initial)
                } catch (_: Exception) { openTree.launch(null) }
            }
            updateSafStatus()
            // Force layout so the section expands/collapses immediately
            audioSection.requestLayout()
            // Also ensure list heights inside audio section refresh
            frontAudioList.updateHeightToContent()
            backAudioList.updateHeightToContent()
        }

        updateSafStatus()
        updateAccessibilityStatus()
        updateAnkiApiStatus()

        // After inserting Display card, recalc list heights to ensure full content is visible
        frontList.updateHeightToContent()
        backList.updateHeightToContent()
        frontAudioList.updateHeightToContent()
        backAudioList.updateHeightToContent()
    }
}
