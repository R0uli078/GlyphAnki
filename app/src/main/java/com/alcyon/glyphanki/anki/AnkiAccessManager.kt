package com.alcyon.glyphanki.anki

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
}


