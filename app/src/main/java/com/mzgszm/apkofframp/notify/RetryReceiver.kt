package com.mzgszm.apkofframp.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mzgszm.apkofframp.graph
import kotlinx.coroutines.launch

/** Retry action on a Needs attention notification. Not exported. */
class RetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val pending = goAsync()
        val g = context.graph
        g.scope.launch {
            try {
                g.notifier.cancelAttention(pkg)
                g.repo.retry(pkg)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        fun intent(context: Context, pkg: String): Intent =
            Intent(context, RetryReceiver::class.java).putExtra(EXTRA_PKG, pkg)
    }
}
