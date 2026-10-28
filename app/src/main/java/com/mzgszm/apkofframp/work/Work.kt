package com.mzgszm.apkofframp.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mzgszm.apkofframp.core.Naming
import com.mzgszm.apkofframp.data.Settings
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

object Work {
    const val PERIODIC_SWEEP = "sweep"
    const val SWEEP_NOW = "sweep-now"
    const val KEY_PKG = "pkg"
    const val KEY_CODE = "versionCode"
    const val KEY_FORCE = "force"

    fun tagFor(pkg: String) = "pkg:$pkg"

    fun schedulePeriodic(context: Context, minutes: Int) {
        val req = PeriodicWorkRequestBuilder<SweepWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_SWEEP, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun sweepNow(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(SWEEP_NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SweepWorker>().build())
    }

    fun constraints(s: Settings): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(s.chargingOnly)
        .build()

    /**
     * One unique job per package and version code, so a sweep and an instant-mode event
     * cannot both start an upload of the same version. KEEP leaves a queued or running job alone.
     */
    fun enqueueUpload(context: Context, pkg: String, versionCode: Long, force: Boolean, settings: Settings) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_PKG to pkg, KEY_CODE to versionCode, KEY_FORCE to force))
            .addTag(tagFor(pkg))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(Naming.uploadWorkName(pkg, versionCode), ExistingWorkPolicy.KEEP, req)
    }

    suspend fun isQueued(context: Context, pkg: String, versionCode: Long): Boolean =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(Naming.uploadWorkName(pkg, versionCode))
            .first()
            .any { !it.state.isFinished }

    suspend fun runningIds(context: Context): List<String> =
        WorkManager.getInstance(context)
            .getWorkInfosFlow(androidx.work.WorkQuery.fromStates(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED))
            .first()
            .map { it.id.toString() }

    fun cancelFor(context: Context, pkg: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tagFor(pkg))
    }
}
