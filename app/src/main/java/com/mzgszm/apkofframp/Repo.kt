package com.mzgszm.apkofframp

import com.mzgszm.apkofframp.core.Naming
import com.mzgszm.apkofframp.core.Publisher
import com.mzgszm.apkofframp.core.RemoteConfig
import com.mzgszm.apkofframp.core.TestReport
import com.mzgszm.apkofframp.core.copyparty.CopypartyClient
import com.mzgszm.apkofframp.core.copyparty.PermissionMissingException
import com.mzgszm.apkofframp.data.AppEntity
import com.mzgszm.apkofframp.data.AppStatus
import com.mzgszm.apkofframp.data.DestinationEntity
import com.mzgszm.apkofframp.work.Work
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolved destination for one app. */
data class AppTarget(val dest: DestinationEntity, val remote: RemoteConfig, val folder: List<String>, val keep: Int)

class Repo(private val g: Graph) {
    private val dao get() = g.dao

    // Destinations

    fun remoteFor(d: DestinationEntity): RemoteConfig =
        RemoteConfig(
            baseUrl = d.baseUrl,
            password = d.passwordEnc?.let { g.secrets.decrypt(it) },
            maxRequestBytes = RemoteConfig.mib(d.maxRequestMiB),
            allowHttp = d.allowHttp,
        )

    fun publisher(remote: RemoteConfig) = Publisher(CopypartyClient(remote, g.http), remote)

    suspend fun destinationFor(app: AppEntity): DestinationEntity? =
        app.destinationId?.let { dao.destination(it) } ?: dao.defaultDestination()

    suspend fun target(app: AppEntity): AppTarget? {
        val dest = destinationFor(app) ?: return null
        val remote = remoteFor(dest)
        val keep = app.keepVersions ?: g.settings.current().defaultKeep
        return AppTarget(dest, remote, remote.folder(app.packageName, app.pathOverride), keep)
    }

    /**
     * Saves a destination. [newPassword] null keeps the stored one, empty clears it.
     * Editing clears a rejected-password mark and re-tests apps paused on it.
     */
    suspend fun saveDestination(d: DestinationEntity, newPassword: String?): Long {
        // Validate before saving so a bad URL never reaches the database.
        RemoteConfig(d.baseUrl, null, RemoteConfig.mib(d.maxRequestMiB), d.allowHttp)
        val enc = when {
            newPassword == null -> d.passwordEnc
            newPassword.isEmpty() -> null
            else -> g.secrets.encrypt(newPassword)
        }
        val id = dao.saveDestination(d.copy(passwordEnc = enc, authFailed = false, hashVerify = if (d.id == 0L) null else d.hashVerify))
        resumePausedFor(id)
        return id
    }

    suspend fun deleteDestination(id: Long) = dao.deleteDestination(id)

    suspend fun testDestination(d: DestinationEntity): TestReport = withContext(Dispatchers.IO) {
        val report = publisher(remoteFor(d)).testConnection()
        val summary = buildString {
            val missing = report.missing()
            append(if (missing.isEmpty()) "All five permissions work" else "Missing: ${missing.joinToString(" ")}")
            append(". Hash verification ")
            append(if (report.hashVerify) "works." else "is not available, sizes are checked instead.")
        }
        dao.updateDestination(d.copy(hashVerify = report.hashVerify, lastTestSummary = summary, authFailed = false))
        report
    }

    suspend fun testRequestSize(d: DestinationEntity) = withContext(Dispatchers.IO) {
        publisher(remoteFor(d)).testRequestSize()
    }

    private suspend fun resumePausedFor(destId: Long) {
        val isDefault = dao.destination(destId)?.isDefault == true
        for (app in dao.pausedFor(destId, isDefault)) retestAndResume(app.packageName)
    }

    // Apps

    suspend fun addApp(pkg: String, label: String, uploadNow: Boolean) {
        require(Naming.isValidPackage(pkg)) { "Not a valid package ID" }
        val existing = dao.app(pkg)
        val snap = g.installed.snapshot(pkg)
        val row = AppEntity(
            packageName = pkg,
            displayName = snap?.label ?: label.ifBlank { pkg },
            enabled = true,
            destinationId = existing?.destinationId,
            pathOverride = existing?.pathOverride,
            keepVersions = existing?.keepVersions,
            lastVersionCode = existing?.lastVersionCode ?: if (!uploadNow) snap?.versionCode else null,
            lastVersionName = existing?.lastVersionName ?: if (!uploadNow) snap?.versionName else null,
            lastUploadTime = existing?.lastUploadTime,
            lastCertDigest = existing?.lastCertDigest,
            uploadOnAppear = uploadNow,
            status = when {
                snap == null -> AppStatus.WAITING
                uploadNow -> AppStatus.PENDING
                else -> AppStatus.UP_TO_DATE
            },
        )
        dao.upsertApp(row)
        if (snap != null && uploadNow) check(pkg)
    }

    suspend fun removeApp(pkg: String) {
        Work.cancelFor(g.app, pkg)
        dao.deleteApp(pkg)
        g.extractor.cleanupOthers(pkg, -1)
    }

    suspend fun updateAppSettings(pkg: String, destinationId: Long?, pathOverride: String?, keep: Int?) {
        val app = dao.app(pkg) ?: return
        val folder = pathOverride?.trim()?.trim('/')?.ifEmpty { null }
        if (folder != null) require(folder.split('/').none { it == "." || it == ".." }) { "Folder cannot contain . or .." }
        dao.upsertApp(app.copy(destinationId = destinationId, pathOverride = folder, keepVersions = keep))
        if (app.status == AppStatus.PAUSED) retestAndResume(pkg)
    }

    suspend fun setEnabled(pkg: String, enabled: Boolean) {
        dao.setEnabled(pkg, enabled)
        if (enabled) check(pkg) else Work.cancelFor(g.app, pkg)
    }

    /**
     * Compares the installed version with the last upload and queues an upload if it is higher.
     * Called by the sweep, instant mode, and after adding an app.
     */
    suspend fun check(pkg: String) {
        val app = dao.app(pkg) ?: return
        if (!app.enabled || app.status == AppStatus.PAUSED) return
        val dest = destinationFor(app)
        if (dest?.authFailed == true) return
        val snap = g.installed.snapshot(pkg)
        if (snap == null) {
            if (app.status != AppStatus.WAITING) dao.setStatus(pkg, AppStatus.WAITING, null)
            return
        }
        val last = app.lastVersionCode
        if (last == null && !app.uploadOnAppear) {
            // Added without "upload now" while not installed: the version it appears with is the baseline.
            dao.upsertApp(app.copy(lastVersionCode = snap.versionCode, lastVersionName = snap.versionName,
                status = AppStatus.UP_TO_DATE, lastError = null))
            return
        }
        if (!Naming.shouldUpload(snap.versionCode, last)) {
            if (app.status == AppStatus.WAITING || app.status == AppStatus.PENDING) {
                if (!Work.isQueued(g.app, pkg, snap.versionCode)) dao.setStatus(pkg, AppStatus.UP_TO_DATE, null)
            }
            return
        }
        if (app.failedVersionCode == snap.versionCode) return
        if (app.status != AppStatus.UPLOADING) dao.setStatus(pkg, AppStatus.PENDING, null)
        Work.enqueueUpload(g.app, pkg, snap.versionCode, force = false, settings = g.settings.current())
    }

    suspend fun checkAll() {
        for (app in dao.apps()) check(app.packageName)
    }

    /** Upload now: sends the installed version even if it was already uploaded. */
    suspend fun uploadNow(pkg: String) {
        val app = dao.app(pkg) ?: return
        val snap = g.installed.snapshot(pkg) ?: throw IllegalStateException("${app.displayName} is not installed")
        if (destinationFor(app) == null) throw IllegalStateException("Add a destination in Settings first")
        dao.upsertApp(app.copy(failedVersionCode = null, status = AppStatus.PENDING, lastError = null))
        Work.enqueueUpload(g.app, pkg, snap.versionCode, force = true, settings = g.settings.current())
    }

    suspend fun retry(pkg: String) {
        val app = dao.app(pkg) ?: return
        dao.upsertApp(app.copy(failedVersionCode = null, lastError = null))
        destinationFor(app)?.let { if (it.authFailed) return }
        if (app.status == AppStatus.PAUSED) retestAndResume(pkg) else {
            dao.setStatus(pkg, AppStatus.PENDING, null)
            check(pkg)
        }
    }

    /** Runs the permission pre-flight. On success the app is unpaused and checked; on failure it stays paused. */
    suspend fun retestAndResume(pkg: String): String? {
        val app = dao.app(pkg) ?: return null
        val t = target(app) ?: return "No destination"
        return try {
            withContext(Dispatchers.IO) { publisher(t.remote).checkPermissions(t.folder, t.keep) }
            dao.setStatus(pkg, AppStatus.PENDING, null)
            g.notifier.cancelAttention(pkg)
            check(pkg)
            null
        } catch (e: PermissionMissingException) {
            val msg = "Missing copyparty permission: ${e.missing.joinToString(", ")}"
            dao.setStatus(pkg, AppStatus.PAUSED, msg)
            msg
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            dao.setStatus(pkg, AppStatus.PAUSED, msg)
            msg
        }
    }

    fun folderLink(t: AppTarget): String = t.remote.url(t.folder, dir = true)
}
