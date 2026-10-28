package com.mzgszm.apkofframp.notify

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

/** The only code in the app that writes to the clipboard. Called from a Copy button or this activity. */
object Clip {
    fun copy(context: Context, label: String, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)!!
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13 and later show their own confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
    }
}

/**
 * A transparent screen for the Copy link notification action. Android limits clipboard access
 * for apps without focus, so this takes focus, writes the link, and closes.
 */
class CopyLinkActivity : Activity() {
    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !done) {
            done = true
            intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("http") }?.let { Clip.copy(this, "APK link", it) }
            finish()
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        fun intent(context: Context, url: String): Intent =
            Intent(context, CopyLinkActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
