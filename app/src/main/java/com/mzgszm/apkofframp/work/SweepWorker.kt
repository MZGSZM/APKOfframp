package com.mzgszm.apkofframp.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mzgszm.apkofframp.graph

/** Reads each monitored app's installed version and queues uploads for anything newer. Cheap. */
class SweepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        g.dao.closeStaleRuns(Work.runningIds(applicationContext), System.currentTimeMillis())
        g.repo.checkAll()
        return Result.success()
    }
}
