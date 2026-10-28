package com.mzgszm.apkofframp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mzgszm.apkofframp.core.Naming
import com.mzgszm.apkofframp.data.Settings
import com.mzgszm.apkofframp.ui.OfframpNav
import com.mzgszm.apkofframp.ui.theme.OfframpTheme
import com.mzgszm.apkofframp.work.InstantModeService
import com.mzgszm.apkofframp.work.Work
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Package to open, from a notification tap. */
    private val openPkg = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        // A sweep runs whenever the app opens.
        Work.sweepNow(this)
        setContent {
            val settings by graph.settings.flow.collectAsStateWithLifecycle(Settings())
            OfframpTheme(settings.theme, settings.dynamicColor) {
                OfframpNav(openPkg = openPkg.value, onOpened = { openPkg.value = null })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Instant mode starts when the app opens, not at boot.
        lifecycleScope.launch {
            if (graph.settings.current().instantMode) InstantModeService.start(this@MainActivity)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "offramp" && data.host == "app") {
            data.lastPathSegment?.takeIf { Naming.isValidPackage(it) }?.let { openPkg.value = it }
        }
    }
}
