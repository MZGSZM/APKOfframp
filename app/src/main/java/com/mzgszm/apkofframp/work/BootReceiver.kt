package com.mzgszm.apkofframp.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mzgszm.apkofframp.graph
import kotlinx.coroutines.launch

/** Re-schedules the sweep after a reboot or an Offramp update, and runs one right away. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val pending = goAsync()
        val g = context.graph
        g.scope.launch {
            try {
                Work.schedulePeriodic(context, g.settings.current().sweepMinutes)
                Work.sweepNow(context)
            } finally {
                pending.finish()
            }
        }
    }
}
