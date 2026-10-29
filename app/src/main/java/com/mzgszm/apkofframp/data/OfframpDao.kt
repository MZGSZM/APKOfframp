package com.mzgszm.apkofframp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class OfframpDao {

    // Apps

    @Query("SELECT * FROM apps ORDER BY displayName COLLATE NOCASE")
    abstract fun observeApps(): Flow<List<AppEntity>>

    @Query("SELECT * FROM apps WHERE packageName = :pkg")
    abstract fun observeApp(pkg: String): Flow<AppEntity?>

    @Query("SELECT * FROM apps ORDER BY displayName COLLATE NOCASE")
    abstract suspend fun apps(): List<AppEntity>

    @Query("SELECT * FROM apps WHERE packageName = :pkg")
    abstract suspend fun app(pkg: String): AppEntity?

    @Upsert
    abstract suspend fun upsertApp(app: AppEntity)

    @Query("DELETE FROM apps WHERE packageName = :pkg")
    abstract suspend fun deleteApp(pkg: String)

    @Query("UPDATE apps SET status = :status, lastError = :error, progress = -1 WHERE packageName = :pkg")
    abstract suspend fun setStatus(pkg: String, status: AppStatus, error: String?)

    @Query("UPDATE apps SET progress = :progress WHERE packageName = :pkg")
    abstract suspend fun setProgress(pkg: String, progress: Int)

    @Query("UPDATE apps SET enabled = :enabled WHERE packageName = :pkg")
    abstract suspend fun setEnabled(pkg: String, enabled: Boolean)

    @Query("UPDATE apps SET failedVersionCode = :code, status = 'FAILED', lastError = :error, progress = -1 WHERE packageName = :pkg")
    abstract suspend fun setPermanentFailure(pkg: String, code: Long, error: String)

    // The writes below touch only their own columns. A whole-row upsert built from an earlier read
    // can undo a change made in between, such as a destination picked while an upload was queued.

    @Query("UPDATE apps SET destinationId = :destinationId, pathOverride = :pathOverride, keepVersions = :keep WHERE packageName = :pkg")
    abstract suspend fun setAppSettings(pkg: String, destinationId: Long?, pathOverride: String?, keep: Int?)

    @Query("UPDATE apps SET lastVersionCode = :code, lastVersionName = :name, status = 'UP_TO_DATE', lastError = NULL, progress = -1 WHERE packageName = :pkg")
    abstract suspend fun setBaseline(pkg: String, code: Long, name: String?)

    /** Clears a failure and marks the app pending, for Upload now and for a move to a new destination. */
    @Query("UPDATE apps SET failedVersionCode = NULL, status = 'PENDING', lastError = NULL, progress = -1 WHERE packageName = :pkg")
    abstract suspend fun markQueued(pkg: String)

    @Query("UPDATE apps SET failedVersionCode = NULL, lastError = NULL WHERE packageName = :pkg")
    abstract suspend fun clearFailure(pkg: String)

    /** Forgets how far an earlier upload got, since that progress belongs to the old destination. */
    @Query("UPDATE apps SET stage = 'NONE', stageVersionCode = NULL WHERE packageName = :pkg")
    abstract suspend fun resetStage(pkg: String)

    /** Blocking, for the stage store that runs inside the upload thread. */
    @Query("UPDATE apps SET stage = :stage, stageVersionCode = :code WHERE packageName = :pkg")
    abstract fun setStageBlocking(pkg: String, code: Long, stage: String)

    @Query("SELECT * FROM apps WHERE packageName = :pkg")
    abstract fun appBlocking(pkg: String): AppEntity?

    @Query("SELECT * FROM apps WHERE status = 'PAUSED' AND (destinationId = :destId OR (:isDefault AND destinationId IS NULL))")
    abstract suspend fun pausedFor(destId: Long, isDefault: Boolean): List<AppEntity>

    // Destinations

    @Query("SELECT * FROM destinations ORDER BY isDefault DESC, name COLLATE NOCASE")
    abstract fun observeDestinations(): Flow<List<DestinationEntity>>

    @Query("SELECT * FROM destinations ORDER BY isDefault DESC, name COLLATE NOCASE")
    abstract suspend fun destinations(): List<DestinationEntity>

    @Query("SELECT * FROM destinations WHERE id = :id")
    abstract suspend fun destination(id: Long): DestinationEntity?

    @Query("SELECT * FROM destinations WHERE isDefault = 1 LIMIT 1")
    abstract suspend fun defaultDestination(): DestinationEntity?

    @Insert
    abstract suspend fun insertDestination(d: DestinationEntity): Long

    @Update
    abstract suspend fun updateDestination(d: DestinationEntity)

    @Query("DELETE FROM destinations WHERE id = :id")
    abstract suspend fun deleteDestinationRow(id: Long)

    @Query("UPDATE destinations SET isDefault = 0")
    abstract suspend fun clearDefault()

    @Query("UPDATE destinations SET authFailed = :failed WHERE id = :id")
    abstract suspend fun setAuthFailed(id: Long, failed: Boolean)

    @Query("UPDATE apps SET destinationId = NULL WHERE destinationId = :id")
    abstract suspend fun detachApps(id: Long)

    @Transaction
    open suspend fun saveDestination(d: DestinationEntity): Long {
        val count = destinations().size
        // Switching the default off on the default itself would leave none, and apps on "Default" would stall.
        val wasDefault = d.id != 0L && destination(d.id)?.isDefault == true
        val makeDefault = d.isDefault || wasDefault || count == 0 || (count == 1 && d.id != 0L)
        if (makeDefault) clearDefault()
        val row = d.copy(isDefault = makeDefault)
        return if (row.id == 0L) insertDestination(row) else { updateDestination(row); row.id }
    }

    @Transaction
    open suspend fun deleteDestination(id: Long) {
        val wasDefault = destination(id)?.isDefault == true
        detachApps(id)
        deleteDestinationRow(id)
        if (wasDefault) destinations().firstOrNull()?.let { updateDestination(it.copy(isDefault = true)) }
    }

    // Versions

    @Query("SELECT * FROM versions WHERE packageName = :pkg ORDER BY CASE state WHEN 'CURRENT' THEN 0 WHEN 'ARCHIVED' THEN 1 ELSE 2 END, versionCode DESC, uploadTime DESC")
    abstract fun observeVersions(pkg: String): Flow<List<VersionEntity>>

    @Query("SELECT * FROM versions WHERE packageName = :pkg AND state = 'CURRENT' ORDER BY uploadTime DESC")
    abstract suspend fun currentVersions(pkg: String): List<VersionEntity>

    @Query("SELECT * FROM versions WHERE packageName = :pkg AND remotePath = :path AND state != 'PRUNED'")
    abstract suspend fun versionsAt(pkg: String, path: String): List<VersionEntity>

    @Insert
    abstract suspend fun insertVersion(v: VersionEntity): Long

    @Update
    abstract suspend fun updateVersion(v: VersionEntity)

    // Upload history

    @Query("SELECT * FROM uploads ORDER BY startTime DESC LIMIT 500")
    abstract fun observeHistory(): Flow<List<UploadEntity>>

    @Query("SELECT * FROM uploads WHERE packageName = :pkg ORDER BY startTime DESC LIMIT 100")
    abstract fun observeHistoryFor(pkg: String): Flow<List<UploadEntity>>

    @Query("SELECT * FROM uploads WHERE workId = :workId")
    abstract suspend fun uploadByWork(workId: String): UploadEntity?

    @Insert
    abstract suspend fun insertUpload(u: UploadEntity): Long

    @Update
    abstract suspend fun updateUpload(u: UploadEntity)

    @Query("UPDATE uploads SET outcome = 'FAILED', finishTime = :now, error = 'Interrupted' WHERE outcome = 'RUNNING' AND workId NOT IN (:running)")
    abstract suspend fun closeStaleRuns(running: List<String>, now: Long)

    @Query("SELECT * FROM versions WHERE packageName = :pkg AND state != 'PRUNED'")
    abstract suspend fun liveVersions(pkg: String): List<VersionEntity>

    @Query("SELECT * FROM versions WHERE id = :id")
    abstract suspend fun version(id: Long): VersionEntity?

    /**
     * Records a finished upload: link updates from [Ledger], the new current version, the app row, and history.
     * Only the upload columns of [app] are written. Destination, folder, versions kept and the enabled switch
     * come from the row as it is now, so a setting changed during the upload survives.
     */
    @Transaction
    open suspend fun recordPublished(
        app: AppEntity,
        updates: List<com.mzgszm.apkofframp.core.LinkUpdate>,
        newVersion: VersionEntity?,
        upload: UploadEntity,
    ) {
        for (u in updates) {
            val v = version(u.id) ?: continue
            val state = when {
                u.pruned -> VersionState.PRUNED
                u.current -> VersionState.CURRENT
                else -> VersionState.ARCHIVED
            }
            updateVersion(v.copy(remotePath = u.remotePath, url = u.url, state = state))
        }
        newVersion?.let { insertVersion(it) }
        val now = app(app.packageName)
        upsertApp(
            now?.copy(
                lastVersionCode = app.lastVersionCode,
                lastVersionName = app.lastVersionName,
                lastUploadTime = app.lastUploadTime,
                lastCertDigest = app.lastCertDigest,
                status = app.status,
                lastError = app.lastError,
                failedVersionCode = app.failedVersionCode,
                progress = app.progress,
                stage = app.stage,
                stageVersionCode = app.stageVersionCode,
            ) ?: app,
        )
        updateUpload(upload)
    }
}
