package com.mzgszm.apkofframp.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.mzgszm.apkofframp.core.InstallState
import java.security.MessageDigest

data class InstalledSnapshot(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val versionName: String?,
    val lastUpdateTime: Long,
    val sourceDir: String,
    val splitSourceDirs: List<String>,
    val splitNames: List<String>,
    val isSystem: Boolean,
) {
    val isSplit: Boolean get() = splitSourceDirs.isNotEmpty()
    fun installState() = InstallState(versionCode, lastUpdateTime)
}

/** Reads what is installed. Needs QUERY_ALL_PACKAGES to see other apps on Android 11 and later. */
class InstalledApps(private val context: Context) {
    private val pm: PackageManager get() = context.packageManager

    fun snapshot(pkg: String): InstalledSnapshot? = try {
        packageInfo(pkg, 0).let(::toSnapshot)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun list(includeSystem: Boolean): List<InstalledSnapshot> {
        val infos = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
        return infos.mapNotNull(::toSnapshot)
            .filter { includeSystem || !it.isSystem }
            .sortedWith(compareBy<InstalledSnapshot>({ it.isSystem }, { it.label.lowercase() }))
    }

    fun icon(pkg: String): Drawable? = try {
        pm.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** SHA-256 of the current signing certificate(s), or null if unavailable. */
    fun signingDigest(pkg: String): String? = try {
        val certs: List<ByteArray> = if (Build.VERSION.SDK_INT >= 28) {
            val info = packageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            val info = packageInfo(pkg, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() } ?: emptyList()
        }
        if (certs.isEmpty()) null else certs.map(::sha256Hex).sorted().joinToString(",")
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Package name and version code of an APK file on disk. */
    fun archiveInfo(path: String): Pair<String, Long>? {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, 0)
        } ?: return null
        return info.packageName to PackageInfoCompat.getLongVersionCode(info)
    }

    private fun packageInfo(pkg: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, flags)
        }

    private fun toSnapshot(info: PackageInfo): InstalledSnapshot? {
        val ai = info.applicationInfo ?: return null
        val source = ai.sourceDir ?: return null
        return InstalledSnapshot(
            packageName = info.packageName,
            label = ai.loadLabel(pm).toString(),
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            versionName = info.versionName,
            lastUpdateTime = info.lastUpdateTime,
            sourceDir = source,
            splitSourceDirs = ai.splitSourceDirs?.toList() ?: emptyList(),
            splitNames = ai.splitNames?.toList() ?: emptyList(),
            isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        )
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
