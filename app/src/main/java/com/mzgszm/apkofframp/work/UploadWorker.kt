package com.mzgszm.apkofframp.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mzgszm.apkofframp.Graph
import com.mzgszm.apkofframp.core.InsecureUrlException
import com.mzgszm.apkofframp.core.KeptLink
import com.mzgszm.apkofframp.core.Ledger
import com.mzgszm.apkofframp.core.Naming
import com.mzgszm.apkofframp.core.PreviousVersion
import com.mzgszm.apkofframp.core.PublishRequest
import com.mzgszm.apkofframp.core.SettleCheck
import com.mzgszm.apkofframp.core.Stage
import com.mzgszm.apkofframp.core.StageStore
import com.mzgszm.apkofframp.core.copyparty.AuthFailedException
import com.mzgszm.apkofframp.core.copyparty.CopypartyException
import com.mzgszm.apkofframp.core.copyparty.PermissionMissingException
import com.mzgszm.apkofframp.core.copyparty.VerifyFailedException
import com.mzgszm.apkofframp.data.AppEntity
import com.mzgszm.apkofframp.data.AppStatus
import com.mzgszm.apkofframp.data.Outcome
import com.mzgszm.apkofframp.data.UploadEntity
import com.mzgszm.apkofframp.data.VersionEntity
import com.mzgszm.apkofframp.data.VersionState
import com.mzgszm.apkofframp.graph
import com.mzgszm.apkofframp.notify.Notifier
import com.mzgszm.apkofframp.system.Extracted
import com.mzgszm.apkofframp.system.InstalledSnapshot
import com.mzgszm.apkofframp.system.NotEnoughSpaceException
import com.mzgszm.apkofframp.system.UnreadableApkException
import com.mzgszm.apkofframp.system.VersionChangedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Settle check, extract, publish (upload + rotate), then record and notify.
 * Network failures return retry(); WorkManager backs off and the chunked upload resumes.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val g: Graph get() = applicationContext.graph
    private val pkg: String get() = inputData.getString(Work.KEY_PKG).orEmpty()
    private val jobCode: Long get() = inputData.getLong(Work.KEY_CODE, -1)
    private val force: Boolean get() = inputData.getBoolean(Work.KEY_FORCE, false)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dao = g.dao
        val app = dao.app(pkg) ?: return@withContext Result.success()
        if (!app.enabled && !force) return@withContext Result.success()

        val dest = g.repo.destinationFor(app)
        if (dest == null) {
            fail(app, "No destination. Add one in Settings.", retryable = false)
            return@withContext Result.failure()
        }
        if (dest.authFailed) return@withContext Result.failure()

        // Settle check, and confirm this job still matches what is installed.
        val settings = g.settings.current()
        val settled = SettleCheck({ g.installed.snapshot(pkg)?.installState() }, { delay(it) })
            .await(settings.settleSeconds * 1000L)
        if (settled == null) {
            if (g.installed.snapshot(pkg) == null) dao.setStatus(pkg, AppStatus.WAITING, null)
            else return@withContext Result.retry()
            return@withContext Result.success()
        }
        if (settled.versionCode != jobCode) {
            // A newer version arrived while this job waited. Let a job for that version handle it.
            g.repo.check(pkg)
            return@withContext Result.success()
        }
        if (!force && !Naming.shouldUpload(settled.versionCode, app.lastVersionCode)) {
            dao.setStatus(pkg, AppStatus.UP_TO_DATE, null)
            return@withContext Result.success()
        }

        val history = dao.uploadByWork(id.toString()) ?: run {
            val snap0 = g.installed.snapshot(pkg)
            val row = UploadEntity(
                workId = id.toString(), packageName = pkg, displayName = app.displayName,
                versionCode = jobCode, versionName = snap0?.versionName,
                startTime = System.currentTimeMillis(), destinationId = dest.id,
            )
            row.copy(id = dao.insertUpload(row))
        }

        var snap: InstalledSnapshot? = null
        try {
            dao.setStatus(pkg, AppStatus.UPLOADING, null)
            dao.setProgress(pkg, 0)

            // Extract. If the app updates mid-copy, start over from the settle check (a few times).
            var extracted: Extracted? = null
            var restarts = 0
            while (extracted == null) {
                val s = g.installed.snapshot(pkg) ?: throw IOException("${app.displayName} was uninstalled")
                if (s.versionCode != jobCode) { g.repo.check(pkg); return@withContext Result.success() }
                snap = s
                notifyProgress(app, s, 0, 0)
                extracted = try {
                    g.extractor.extract(s)
                } catch (e: VersionChangedException) {
                    if (++restarts > 3) throw e
                    SettleCheck({ g.installed.snapshot(pkg)?.installState() }, { delay(it) })
                        .await(settings.settleSeconds * 1000L)
                    null
                }
            }
            val s = snap!!
            val done: Extracted = extracted ?: error("unreachable")
            g.extractor.cleanupOthers(pkg, s.versionCode)

            val certChanged = app.lastCertDigest != null && done.certDigest != null &&
                app.lastCertDigest != done.certDigest

            val target = g.repo.target(app) ?: throw IllegalStateException("No destination")
            val pub = g.repo.publisher(target.remote)
            val store = DbStageStore(pkg, s.versionCode)
            var lastPct = -1
            var lastAt = 0L
            val result = pub.publish(
                PublishRequest(
                    pkg = pkg,
                    versionCode = s.versionCode,
                    versionName = s.versionName,
                    file = done.upload,
                    ext = done.ext,
                    folder = target.folder,
                    keep = target.keep,
                    previous = app.lastVersionCode?.takeIf { app.lastUploadTime != null }
                        ?.let { PreviousVersion(app.lastVersionName, it) },
                    lmodSeconds = s.lastUpdateTime / 1000,
                    hashVerify = dest.hashVerify != false,
                ),
                store,
            ) { sent, total ->
                // The upload is blocking I/O, so coroutine cancellation alone would not stop it.
                if (isStopped) throw CancellationException("Work stopped")
                val pct = if (total > 0) ((sent * 100) / total).toInt() else 0
                val now = System.currentTimeMillis()
                if (pct != lastPct && now - lastAt > 500) {
                    lastPct = pct
                    lastAt = now
                    runBlocking { dao.setProgress(pkg, pct) }
                    notifyProgress(app, s, sent, total)
                }
            }

            // Record.
            val now = System.currentTimeMillis()
            val live = dao.liveVersions(pkg)
            val updates = Ledger.afterPublish(
                target.remote, target.folder,
                live.map { KeptLink(it.id, it.remotePath, it.url, it.state == VersionState.CURRENT, false) },
                result,
            )
            val currentPath = Naming.vpath(result.currentPath)
            val currentUrl = target.remote.url(result.currentPath)
            val alreadyRecorded = result.alreadyPresent && live.any { it.state == VersionState.CURRENT && it.versionCode == s.versionCode }
            val newVersion = if (alreadyRecorded) null else VersionEntity(
                packageName = pkg, versionCode = s.versionCode, versionName = s.versionName,
                size = done.upload.length(), sha256 = done.sha256, uploadTime = now,
                destinationId = dest.id, state = VersionState.CURRENT,
                remotePath = currentPath, url = currentUrl, certChanged = certChanged,
            )
            val fresh = dao.app(pkg) ?: app
            val updatedApp = fresh.copy(
                lastVersionCode = s.versionCode,
                lastVersionName = s.versionName,
                lastUploadTime = now,
                lastCertDigest = done.certDigest ?: fresh.lastCertDigest,
                status = AppStatus.UP_TO_DATE,
                lastError = result.warnings.firstOrNull(),
                failedVersionCode = null,
                progress = -1,
                stage = Stage.NONE.name,
                stageVersionCode = null,
            )
            val outcome = if (result.alreadyPresent) Outcome.SKIPPED else Outcome.UPLOADED
            dao.recordPublished(
                updatedApp, updates, newVersion,
                history.copy(
                    finishTime = now, outcome = outcome, size = done.upload.length(),
                    versionName = s.versionName,
                    error = when {
                        result.alreadyPresent -> "Already on the server"
                        certChanged -> "Signing certificate changed"
                        else -> null
                    },
                ),
            )

            g.extractor.cleanup(pkg, s.versionCode)
            g.notifier.cancelProgress(pkg)
            g.notifier.cancelAttention(pkg)
            g.notifier.completed(pkg, app.displayName, s.versionName ?: s.versionCode.toString(), currentUrl)
            if (certChanged) g.notifier.certChanged(pkg, app.displayName, s.versionName ?: s.versionCode.toString())
            Result.success()
        } catch (e: CancellationException) {
            // Stopped by the system or cancelled by the user. Keep the work directory so a rerun resumes.
            g.notifier.cancelProgress(pkg)
            withContext(kotlinx.coroutines.NonCancellable) {
                if (isStopped) dao.setStatus(pkg, AppStatus.PENDING, null)
            }
            throw e
        } catch (e: AuthFailedException) {
            dao.setAuthFailed(dest.id, true)
            finishHistory(history, e.message)
            fail(app, "Password rejected by ${dest.name}. Check the password; uploads to it stop until it is edited.", retryable = false, title = "Check the password")
            Result.failure()
        } catch (e: PermissionMissingException) {
            finishHistory(history, e.message)
            dao.setStatus(pkg, AppStatus.PAUSED, e.message)
            g.notifier.cancelProgress(pkg)
            g.notifier.attention(pkg, "${app.displayName} paused",
                "${e.message}. Fix it on the server, then tap Re-test and resume.", retry = true)
            Result.failure()
        } catch (e: UnreadableApkException) {
            finishHistory(history, e.message)
            snap?.let { g.extractor.cleanup(pkg, it.versionCode) }
            dao.setPermanentFailure(pkg, jobCode, e.message ?: "Cannot read the install files")
            g.notifier.cancelProgress(pkg)
            g.notifier.attention(pkg, "Cannot read ${app.displayName}", e.message ?: "Permission denied", retry = false)
            Result.failure()
        } catch (e: NotEnoughSpaceException) {
            finishHistory(history, e.message)
            fail(app, e.message ?: "Not enough free space", retryable = true)
            Result.failure()
        } catch (e: VerifyFailedException) {
            finishHistory(history, e.message)
            fail(app, e.message ?: "Verification failed", retryable = true)
            Result.failure()
        } catch (e: InsecureUrlException) {
            finishHistory(history, e.message)
            fail(app, e.message ?: "Plain HTTP not allowed", retryable = false)
            Result.failure()
        } catch (e: IllegalArgumentException) {
            finishHistory(history, e.message)
            fail(app, e.message ?: "Bad destination settings", retryable = false)
            Result.failure()
        } catch (e: IOException) {
            val clientError = e is CopypartyException && e.code in 400..499
            if (!clientError && runAttemptCount < MAX_ATTEMPTS) {
                dao.setStatus(pkg, AppStatus.PENDING, "Retrying: ${e.message}")
                g.notifier.cancelProgress(pkg)
                Result.retry()
            } else {
                finishHistory(history, e.message)
                fail(app, e.message ?: "Upload failed", retryable = true)
                Result.failure()
            }
        } catch (e: Exception) {
            finishHistory(history, e.message)
            fail(app, e.message ?: e.javaClass.simpleName, retryable = true)
            Result.failure()
        }
    }

    private suspend fun finishHistory(h: UploadEntity, error: String?) {
        g.dao.updateUpload(h.copy(finishTime = System.currentTimeMillis(), outcome = Outcome.FAILED, error = error))
    }

    private suspend fun fail(app: AppEntity, message: String, retryable: Boolean, title: String? = null) {
        g.dao.setStatus(app.packageName, AppStatus.FAILED, message)
        g.notifier.cancelProgress(app.packageName)
        g.notifier.attention(app.packageName, title ?: "Upload failed: ${app.displayName}", message, retryable)
    }

    private fun notifyProgress(app: AppEntity, s: InstalledSnapshot, sent: Long, total: Long) {
        g.notifier.progress(pkg, app.displayName, s.versionName ?: s.versionCode.toString(), sent, total,
            Notifier.cancelIntent(applicationContext, id))
    }

    /** Persists the rotation stage in the app row so a retry knows where the last attempt stopped. */
    private inner class DbStageStore(private val pkg: String, private val code: Long) : StageStore {
        override fun load(): Stage {
            val row = g.dao.appBlocking(pkg) ?: return Stage.NONE
            if (row.stageVersionCode != code) return Stage.NONE
            return runCatching { Stage.valueOf(row.stage) }.getOrDefault(Stage.NONE)
        }
        override fun save(stage: Stage) = g.dao.setStageBlocking(pkg, code, stage.name)
    }

    companion object {
        const val MAX_ATTEMPTS = 8
    }
}
