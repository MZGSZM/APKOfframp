package com.mzgszm.apkofframp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AppStatus { WAITING, UP_TO_DATE, PENDING, UPLOADING, FAILED, PAUSED }

enum class VersionState { CURRENT, ARCHIVED, PRUNED }

enum class Outcome { RUNNING, UPLOADED, FAILED, SKIPPED }

@Entity(tableName = "apps")
data class AppEntity(
    @PrimaryKey val packageName: String,
    val displayName: String,
    val enabled: Boolean = true,
    /** Null means the default destination. */
    val destinationId: Long? = null,
    /** Folder relative to the destination base URL. Null means the package name. */
    val pathOverride: String? = null,
    /** Null means the global default. -1 means keep all. */
    val keepVersions: Int? = null,
    /** Last uploaded version code, or the baseline when added without an upload. */
    val lastVersionCode: Long? = null,
    val lastVersionName: String? = null,
    /** Null when lastVersionCode is only a baseline. */
    val lastUploadTime: Long? = null,
    val lastCertDigest: String? = null,
    val status: AppStatus = AppStatus.WAITING,
    val lastError: String? = null,
    /** Set when this version failed in a way retrying cannot fix (unreadable files). */
    val failedVersionCode: Long? = null,
    /** Upload the installed version on the next sweep instead of taking it as the baseline. */
    val uploadOnAppear: Boolean = true,
    /** 0 to 100 while uploading, -1 otherwise. */
    val progress: Int = -1,
    /** Which version [stage] belongs to. */
    val stageVersionCode: Long? = null,
    val stage: String = "NONE",
    val addedTime: Long = System.currentTimeMillis(),
)

@Entity(tableName = "destinations")
data class DestinationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUrl: String,
    /** Keystore-encrypted, never logged, never in a link. */
    val passwordEnc: String? = null,
    val maxRequestMiB: Int = 32,
    val allowHttp: Boolean = false,
    val isDefault: Boolean = false,
    /** Null until Test connection has run. */
    val hashVerify: Boolean? = null,
    /** Set when the server rejected the password. Cleared when the destination is edited. */
    val authFailed: Boolean = false,
    val lastTestSummary: String? = null,
)

@Entity(tableName = "versions", indices = [Index("packageName")])
data class VersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val size: Long,
    val sha256: String,
    val uploadTime: Long,
    val destinationId: Long,
    val state: VersionState,
    /** Decoded server path, such as /apks/com.example.app/com.example.app.apk. */
    val remotePath: String,
    /** Percent-encoded link without the password. Empty once pruned. */
    val url: String,
    val certChanged: Boolean = false,
)

@Entity(tableName = "uploads", indices = [Index("packageName"), Index(value = ["workId"], unique = true)])
data class UploadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workId: String,
    val packageName: String,
    val displayName: String,
    val versionCode: Long,
    val versionName: String?,
    val startTime: Long,
    val finishTime: Long? = null,
    val outcome: Outcome = Outcome.RUNNING,
    val size: Long = 0,
    val destinationId: Long? = null,
    val error: String? = null,
)
