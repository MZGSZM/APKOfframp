package com.mzgszm.apkofframp

import android.app.Application
import android.content.Context
import com.mzgszm.apkofframp.data.OfframpDb
import com.mzgszm.apkofframp.data.SecretBox
import com.mzgszm.apkofframp.data.SettingsStore
import com.mzgszm.apkofframp.notify.Notifier
import com.mzgszm.apkofframp.system.Extractor
import com.mzgszm.apkofframp.system.InstalledApps
import com.mzgszm.apkofframp.work.Work
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Everything the app shares, built once. Plain constructor wiring, no DI framework. */
class Graph(context: Context) {
    val app: Context = context.applicationContext
    val db: OfframpDb = OfframpDb.open(app)
    val dao get() = db.dao()
    val settings = SettingsStore(app)
    val secrets = SecretBox()
    val installed = InstalledApps(app)
    val extractor = Extractor(app, installed)
    val notifier = Notifier(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** No logging interceptor on purpose: the PW header must never reach a log. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    val repo = Repo(this)
}

class OfframpApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        graph.notifier.ensureChannels()
        graph.scope.launch {
            Work.schedulePeriodic(this@OfframpApp, graph.settings.current().sweepMinutes)
        }
    }
}

val Context.graph: Graph get() = (applicationContext as OfframpApp).graph
