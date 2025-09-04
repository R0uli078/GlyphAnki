package com.alcyon.glyphanki.anki

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

class AnkiAccessManager(private val context: Context) {
    private val ankiPkgs = listOf("com.ichi2.anki", "com.ichi2.anki.debug")

    fun isAnkiInstalled(): Boolean {
        val pm = context.packageManager
        return ankiPkgs.any { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        }
    }

    fun openPermissionRequest(activity: Activity) {
        // Try documented permission request on any installed package
        val pm = context.packageManager
        val target = ankiPkgs.firstOrNull { pkg -> runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess }
        if (target != null) {
            val intent = Intent("com.ichi2.anki.api.action.REQUEST_PERMISSION").apply {
                `package` = target
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                activity.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) { /* fallback below */ }
        }
        Toast.makeText(context, "Open AnkiDroid > Settings > Advanced > Enable API", Toast.LENGTH_LONG).show()
        // Attempt to open AnkiDroid main
        val launch = ankiPkgs.firstNotNullOfOrNull { pkg -> context.packageManager.getLaunchIntentForPackage(pkg) }
        if (launch != null) {
            try { activity.startActivity(launch) } catch (_: Exception) {}
        }
    }

    fun openDbPermissionRequest(activity: Activity) {
        // Prefer opening GlyphAnki's app-specific permissions inside AnkiDroid if exposed
        val appPkg = context.packageName
        val uri = Uri.parse("anki://permission?package=" + appPkg)
        val deepIntent = Intent(Intent.ACTION_VIEW, uri)
        val resolved = context.packageManager.queryIntentActivities(deepIntent, 0)
        if (resolved.isNotEmpty()) {
            runCatching { activity.startActivity(deepIntent) }.onFailure { /* fallback below */ }
            return
        }
        // Fallback 1: Generic AnkiDroid permission request
        val pm = context.packageManager
        val target = ankiPkgs.firstOrNull { pkg -> runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess }
        if (target != null) {
            val intent = Intent("com.ichi2.anki.api.action.REQUEST_PERMISSION").apply {
                `package` = target
                putExtra("com.ichi2.anki.api.extra.PERMISSION", "READ_WRITE")
                putExtra("permission", "READ_WRITE")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                activity.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) { /* fallback below */ }
        }
        // Fallback 2: Open OS app details so the user can check permissions/links, plus toast instruction
        Toast.makeText(context, "In AnkiDroid: Settings > Advanced > Enable third‑party and grant Read/Write DB to GlyphAnki", Toast.LENGTH_LONG).show()
        val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", ankiPkgs.firstOrNull() ?: "com.ichi2.anki", null)
        }
        runCatching { activity.startActivity(settingsIntent) }
    }
}


