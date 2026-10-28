package com.mzgszm.apkofframp.work

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mzgszm.apkofframp.graph
import com.mzgszm.apkofframp.notify.Notifier
import kotlinx.coroutines.launch

/**
 * Instant mode: a foreground service holding a runtime-registered receiver for package
 * updates, since manifest receivers cannot get those broadcasts. Started from the app, never at boot.
 */
class InstantModeService : Service() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            // ACTION_PACKAGE_ADDED with EXTRA_REPLACING is followed by PACKAGE_REPLACED; checking twice is harmless.
            val pending = goAsync()
            val g = context.graph
            g.scope.launch {
                try {
                    if (g.dao.app(pkg) != null) g.repo.check(pkg)
                } finally {
                    pending.finish()
                }
            }
        }
    }
    private var registered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.INSTANT_ID, graph.notifier.instantNotification(), type)
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addDataScheme("package")
            }
            // System broadcasts arrive even when not exported.
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (registered) unregisterReceiver(receiver)
        registered = false
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, InstantModeService::class.java))
            } catch (e: IllegalStateException) {
                // Not allowed from the background right now; the sweep covers the gap.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InstantModeService::class.java))
        }
    }
}
