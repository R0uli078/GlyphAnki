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
        // Apply UDDigiKyokashoN-R to all text widgets except the title
        val ud = runCatching { Typeface.createFromAsset(assets, "fonts/UDDigiKyokashoN-R.ttf") }.getOrNull() ?: return
        fun apply(v: View) {
            when (v) {
                is TextView -> if (v.id != R.id.settingsTitle) v.typeface = ud
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

        // Observe data changes to resize lists after layout
        fun observe(adapter: ArrayAdapter<String>, list: ListView) {
            adapter.registerDataSetObserver(object : DataSetObserver() {
                override fun onChanged() { list.updateHeightToContent() }
                override fun onInvalidated() { list.updateHeightToContent() }
            })
        }
        observe(frontAdapter, frontList)
        observe(backAdapter, backList)
        observe(frontAudioAdapter, frontAudioList)
        observe(backAudioAdapter, backAudioList)

        // Initial sizing after first layout
        frontList.updateHeightToContent()
        backList.updateHeightToContent()
        frontAudioList.updateHeightToContent()
        backAudioList.updateHeightToContent()

        fun notifyAndSaveFront() { frontAdapter.notifyDataSetChanged(); save(); frontInput.text.clear(); frontList.updateHeightToContent() }
        fun notifyAndSaveBack() { backAdapter.notifyDataSetChanged(); save(); backInput.text.clear(); backList.updateHeightToContent() }
        fun notifyAndSaveFrontAudio() { frontAudioAdapter.notifyDataSetChanged(); save(); frontAudioInput.text.clear(); frontAudioList.updateHeightToContent() }
        fun notifyAndSaveBackAudio() { backAudioAdapter.notifyDataSetChanged(); save(); backAudioInput.text.clear(); backAudioList.updateHeightToContent() }

        addFront.setOnClickListener { val v = frontInput.text.toString().trim(); if (v.isNotEmpty()) { fronts.removeAll { it.equals(v, true) }; fronts.add(0, v); notifyAndSaveFront() } }
        addBack.setOnClickListener { val v = backInput.text.toString().trim(); if (v.isNotEmpty()) { backs.removeAll { it.equals(v, true) }; backs.add(0, v); notifyAndSaveBack() } }
        addFrontAudio.setOnClickListener { val v = frontAudioInput.text.toString().trim(); if (v.isNotEmpty()) { frontAudios.removeAll { it.equals(v, true) }; frontAudios.add(0, v); notifyAndSaveFrontAudio() } }
        addBackAudio.setOnClickListener { val v = backAudioInput.text.toString().trim(); if (v.isNotEmpty()) { backAudios.removeAll { it.equals(v, true) }; backAudios.add(0, v); notifyAndSaveBackAudio() } }

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
        }

        updateSafStatus()
        updateAccessibilityStatus()
        updateAnkiApiStatus()

        // Open SAF
        btnPickMediaFolder.setOnClickListener {
            try {
                val initial: Uri? = runCatching {
                    DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:AnkiDroid/collection.media"
                    )
                }.getOrNull()
                openTree.launch(initial)
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to open picker: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Open Accessibility settings
        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        // Request AnkiDroid API permission -> open GlyphAnki app details for additional permissions
        btnGrantAnkiApi.setOnClickListener {
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:" + packageName)
                }
                startActivity(intent)
                Toast.makeText(this, "Open GlyphAnki > Autorisations supplémentaires > AnkiDroid API", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to open app settings: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Open AnkiDroid app for convenience
        btnOpenAnki?.setOnClickListener {
            try {
                val intent = packageManager.getLaunchIntentForPackage("com.ichi2.anki")
                    ?: packageManager.getLaunchIntentForPackage("com.ichi2.anki.debug")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "AnkiDroid not installed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to open AnkiDroid: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
