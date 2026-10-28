package com.mzgszm.apkofframp.system

import android.content.Context
import com.mzgszm.apkofframp.core.Bundler
import com.mzgszm.apkofframp.core.Naming
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest

class UnreadableApkException(message: String) : IOException(message)
class NotEnoughSpaceException(val needed: Long, val free: Long) :
    IOException("Not enough free space: need ${needed / 1_048_576} MiB, have ${free / 1_048_576} MiB")
class VersionChangedException : IOException("The app updated again during the copy")

data class Extracted(
    val dir: File,
    /** The single file to upload: the APK itself, or the .apks bundle. */
    val upload: File,
    val ext: String,
    val sha256: String,
    val certDigest: String?,
)

/**
 * Copies the installed base APK and splits into private cache with plain file reads,
 * verifies the copy, and bundles splits into one unmodified .apks.
 */
class Extractor(private val context: Context, private val apps: InstalledApps) {

    fun workDir(pkg: String, versionCode: Long) = File(context.cacheDir, "work/$pkg/$versionCode")

    fun cleanup(pkg: String, versionCode: Long) {
        workDir(pkg, versionCode).deleteRecursively()
    }

    /** Removes work directories for other versions of this package. */
    fun cleanupOthers(pkg: String, keepVersionCode: Long) {
        File(context.cacheDir, "work/$pkg").listFiles()?.forEach { dir ->
            if (dir.name != keepVersionCode.toString()) dir.deleteRecursively()
        }
    }

    fun extract(snap: InstalledSnapshot): Extracted {
        val sources = listOf(File(snap.sourceDir)) + snap.splitSourceDirs.map(::File)
        for (src in sources) {
            if (!src.canRead()) throw UnreadableApkException("Cannot read ${src.name}: permission denied")
        }
        val total = sources.sumOf { it.length() }
        val dir = workDir(snap.packageName, snap.versionCode).apply { mkdirs() }
        val already = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val free = dir.usableSpace
        if (free + already < total * 2) throw NotEnoughSpaceException(total * 2, free + already)

        // Copy each file, keeping its name. A copy from an earlier attempt of the same version is reused.
        val copies = sources.map { src ->
            val dst = File(dir, src.name)
            if (!(dst.exists() && dst.length() == src.length())) copyFile(src, dst)
            dst
        }

        val base = copies.first()
        val info = apps.archiveInfo(base.path) ?: throw IOException("The copied base APK could not be parsed")
        if (info.first != snap.packageName || info.second != snap.versionCode) {
            dir.deleteRecursively()
            throw IOException("The copied base APK does not match the installed app")
        }

        // If the install changed while copying, the copy is a mix of versions. Start over.
        val now = apps.snapshot(snap.packageName)
        if (now == null || now.versionCode != snap.versionCode || now.lastUpdateTime != snap.lastUpdateTime) {
            dir.deleteRecursively()
            throw VersionChangedException()
        }

        val ext = Naming.extension(snap.isSplit)
        val upload = if (!snap.isSplit) {
            base
        } else {
            val bundle = File(dir, Naming.stableName(snap.packageName, ext))
            val marker = File(dir, ".bundle-done")
            if (!(bundle.exists() && marker.exists())) {
                marker.delete()
                Bundler.bundle(copies, bundle, snap.lastUpdateTime)
                marker.writeText("ok")
            }
            bundle
        }

        return Extracted(dir, upload, ext, sha256(upload), apps.signingDigest(snap.packageName))
    }

    private fun copyFile(src: File, dst: File) {
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        try {
            src.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
        } catch (e: SecurityException) {
            throw UnreadableApkException("Cannot read ${src.name}: ${e.message}")
        }
        if (tmp.length() != src.length()) throw IOException("Short copy of ${src.name}")
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) throw IOException("Could not move the copy of ${src.name} into place")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        DigestInputStream(f.inputStream(), md).use { input ->
            val buf = ByteArray(256 * 1024)
            while (input.read(buf) >= 0) Unit
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
