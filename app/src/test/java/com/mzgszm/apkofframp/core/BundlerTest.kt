package com.mzgszm.apkofframp.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.random.Random

class BundlerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, size: Int, seed: Int): File =
        File(tmp.root, name).apply { writeBytes(Random(seed).nextBytes(size)) }

    @Test fun entriesAreByteIdenticalAndStored() {
        val files = listOf(
            file("split_config.arm64_v8a.apk", 300_000, 1),
            file("base.apk", 1_200_000, 2),
            file("split_config.en.apk", 7, 3),
            file("split_config.xxhdpi.apk", 0, 4),
        )
        val out = File(tmp.root, "com.x.y.apks")
        Bundler.bundle(files, out, 1_700_000_000_000)

        ZipFile(out).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(listOf("base.apk", "split_config.arm64_v8a.apk", "split_config.en.apk", "split_config.xxhdpi.apk"),
                entries.map { it.name })
            for (e in entries) {
                assertEquals(ZipEntry.STORED, e.method)
                val original = File(tmp.root, e.name).readBytes()
                assertArrayEquals(e.name, original, zip.getInputStream(e).readBytes())
            }
        }
        assertEquals(false, File(tmp.root, "com.x.y.apks.tmp").exists())
    }

    @Test fun sameInputsGiveSameBytes() {
        // A retried bundle must hash the same so an interrupted upload resumes.
        val files = listOf(file("base.apk", 50_000, 5), file("split_a.apk", 9_000, 6))
        val a = File(tmp.root, "a.apks")
        val b = File(tmp.root, "b.apks")
        Bundler.bundle(files, a, 1_700_000_000_000)
        Bundler.bundle(files, b, 1_700_000_000_000)
        assertArrayEquals(a.readBytes(), b.readBytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateNamesRefused() {
        val d1 = File(tmp.newFolder("x"), "base.apk").apply { writeText("1") }
        val d2 = File(tmp.newFolder("y"), "base.apk").apply { writeText("2") }
        Bundler.bundle(listOf(d1, d2), File(tmp.root, "z.apks"), 0)
    }
}
