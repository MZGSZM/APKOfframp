package com.mzgszm.apkofframp.core

import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Zips a base APK and its splits into one `.apks` file: a plain ZIP, stored without
 * compression, holding each file byte for byte. Nothing is merged or re-signed.
 *
 * The output is deterministic for the same inputs and time, so a retried bundle hashes
 * the same and an interrupted up2k upload resumes instead of starting over.
 */
object Bundler {
    /** Base APK first, then splits by name. */
    fun order(files: List<File>): List<File> =
        files.sortedWith(compareBy<File>({ it.name != "base.apk" }, { it.name }))

    fun bundle(files: List<File>, out: File, entryTimeMillis: Long) {
        require(files.isNotEmpty()) { "Nothing to bundle" }
        val names = files.map { it.name }
        require(names.toSet().size == names.size) { "Duplicate file names in the APK set" }
        val tmp = File(out.parentFile, out.name + ".tmp")
        ZipOutputStream(BufferedOutputStream(tmp.outputStream(), 256 * 1024)).use { zos ->
            zos.setMethod(ZipOutputStream.STORED)
            for (f in order(files)) {
                val entry = ZipEntry(f.name).apply {
                    method = ZipEntry.STORED
                    size = f.length()
                    compressedSize = f.length()
                    crc = crcOf(f)
                    time = entryTimeMillis
                }
                zos.putNextEntry(entry)
                f.inputStream().use { it.copyTo(zos, 64 * 1024) }
                zos.closeEntry()
            }
        }
        if (out.exists()) out.delete()
        check(tmp.renameTo(out)) { "Could not move the bundle into place" }
    }

    private fun crcOf(f: File): Long {
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        f.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }
}
