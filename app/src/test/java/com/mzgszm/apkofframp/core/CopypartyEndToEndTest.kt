package com.mzgszm.apkofframp.core

import com.mzgszm.apkofframp.core.copyparty.AuthFailedException
import com.mzgszm.apkofframp.core.copyparty.CopypartyClient
import com.mzgszm.apkofframp.core.copyparty.CopypartyException
import com.mzgszm.apkofframp.core.copyparty.PermissionMissingException
import com.mzgszm.apkofframp.core.copyparty.Up2k
import com.mzgszm.apkofframp.core.copyparty.Up2kUploader
import okhttp3.OkHttpClient
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random

/**
 * The up2k client and rotation end to end against a real copyparty: first upload, a second version
 * archiving the first, pruning, keeping all, interrupted uploads resuming, requests above the cap
 * sent in pieces, and missing permissions reported by name.
 */
class CopypartyEndToEndTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pkg = "com.example.app"
    private var counter = 0

    companion object {
        private lateinit var root: File
        private lateinit var server: CopypartyHarness
        private lateinit var proxy: CapProxy

        @BeforeClass @JvmStatic fun start() {
            root = Files.createTempDirectory("offramp-copyparty").toFile()
            server = CopypartyHarness(root)
            proxy = CapProxy(server.baseUrl)
        }

        @AfterClass @JvmStatic fun stop() {
            if (::proxy.isInitialized) proxy.close()
            if (::server.isInitialized) server.close()
            if (::root.isInitialized) root.deleteRecursively()
        }
    }

    @Before fun resetProxy() {
        proxy.reset()
        proxy.capBytes = Long.MAX_VALUE
    }

    // Helpers

    private val http = OkHttpClient()

    /** Each test gets its own app folder so tests do not see each other's files. */
    private fun appFolderName() = "$pkg.t${System.nanoTime()}"

    private fun remote(volume: String = "apks", password: String? = "hunter", maxRequest: Long = RemoteConfig.mib(32)) =
        RemoteConfig("${proxy.baseUrl}/$volume/", password, maxRequest, allowHttp = true)

    private fun publisher(r: RemoteConfig) = Publisher(CopypartyClient(r, http), r)

    private fun apk(size: Int): File = tmp.newFile("v${counter++}.apk").apply { writeBytes(Random(counter * 7919).nextBytes(size)) }

    private fun request(
        r: RemoteConfig, folderName: String, code: Long, file: File, keep: Int = 3,
        previous: PreviousVersion? = null, ext: String = "apk",
    ) = PublishRequest(
        pkg = pkg, versionCode = code, versionName = "1.$code", file = file, ext = ext,
        folder = r.folder(pkg, folderName), keep = keep, previous = previous,
        lmodSeconds = 1_700_000_000 + code, hashVerify = true,
    )

    private fun disk(volume: String, folderName: String) = File(server.dirs.getValue(volume), folderName)

    /** Publishes versions 1..n in order, each archiving the one before. */
    private fun publishSeries(r: RemoteConfig, folderName: String, n: Int, keep: Int): Map<Long, File> {
        val files = mutableMapOf<Long, File>()
        for (code in 1L..n) {
            val f = apk(40_000 + code.toInt())
            files[code] = f
            val prev = if (code > 1) PreviousVersion("1.${code - 1}", code - 1) else null
            publisher(r).publish(request(r, folderName, code, f, keep, prev), MemoryStageStore()) { _, _ -> }
        }
        return files
    }

    // Tests

    @Test fun firstUpload() {
        val r = remote()
        val name = appFolderName()
        val f = apk(2_500_000)
        val res = publisher(r).publish(request(r, name, 10, f), MemoryStageStore()) { _, _ -> }

        val dir = disk("apks", name)
        assertArrayEquals(f.readBytes(), File(dir, "$pkg.apk").readBytes())
        assertFalse("nothing to archive on a first upload", File(dir, "older").exists())
        assertEquals(emptyList<Archived>(), res.archived)
        assertEquals("hash", res.verifiedBy)
        assertEquals(r.folder(pkg, name) + "$pkg.apk", res.currentPath)
        assertTrue("no staging leftovers", dir.listFiles()!!.none { it.name.startsWith(".incoming-") })
    }

    @Test fun secondVersionArchivesTheFirst() {
        val r = remote()
        val name = appFolderName()
        val v1 = apk(300_000)
        val v2 = apk(310_000)
        publisher(r).publish(request(r, name, 1, v1), MemoryStageStore()) { _, _ -> }
        val res = publisher(r).publish(request(r, name, 2, v2, previous = PreviousVersion("1.1", 1)), MemoryStageStore()) { _, _ -> }

        val dir = disk("apks", name)
        assertArrayEquals(v2.readBytes(), File(dir, "$pkg.apk").readBytes())
        assertArrayEquals(v1.readBytes(), File(dir, "older/${pkg}_1.1_1.apk").readBytes())
        assertEquals(listOf(Archived("$pkg.apk", r.folder(pkg, name) + "older" + "${pkg}_1.1_1.apk")), res.archived)
    }

    @Test fun pruningPastTheLimit() {
        val r = remote()
        val name = appFolderName()
        val files = publishSeries(r, name, n = 5, keep = 2)

        val older = disk("apks", name).resolve("older").list()!!.sorted()
        assertEquals(listOf("${pkg}_1.3_3.apk", "${pkg}_1.4_4.apk"), older)
        assertArrayEquals(files.getValue(5).readBytes(), File(disk("apks", name), "$pkg.apk").readBytes())
    }

    @Test fun keepZeroDeletesEveryOlderVersion() {
        val r = remote()
        val name = appFolderName()
        publishSeries(r, name, n = 3, keep = 0)
        assertEquals(emptyList<String>(), disk("apks", name).resolve("older").list()!!.toList())
    }

    @Test fun keepAllNeedsNoDeletePermission() {
        val r = remote("keepall")
        val name = appFolderName()
        publishSeries(r, name, n = 4, keep = Naming.KEEP_ALL)
        val older = disk("keepall", name).resolve("older").list()!!.sorted()
        assertEquals(listOf("${pkg}_1.1_1.apk", "${pkg}_1.2_2.apk", "${pkg}_1.3_3.apk"), older)
    }

    @Test fun limitedKeepFailsWithoutDeletePermission() {
        val r = remote("keepall")
        try {
            publisher(r).publish(request(r, appFolderName(), 1, apk(1000), keep = 3), MemoryStageStore()) { _, _ -> }
            fail("expected a missing permission")
        } catch (e: PermissionMissingException) {
            assertEquals(listOf("d"), e.missing)
        }
    }

    @Test fun interruptedUploadResumes() {
        val r = remote()
        val name = appFolderName()
        val f = apk(6 * 1024 * 1024 + 500) // 1 MiB chunks: 7 of them
        val chunks = Up2k.hashFile(f).size
        assertEquals(7, chunks)
        val store = MemoryStageStore()

        proxy.failChunksAfter = 2
        try {
            publisher(r).publish(request(r, name, 3, f), store) { _, _ -> }
            fail("the proxy should have cut the upload")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(Stage.UPLOADING, store.stage)
        assertFalse(File(disk("apks", name), "$pkg.apk").exists())

        proxy.reset()
        publisher(r).publish(request(r, name, 3, f), store) { _, _ -> }
        assertEquals("only the missing chunks are sent", chunks - 2, proxy.chunkPosts.get())
        assertArrayEquals(f.readBytes(), File(disk("apks", name), "$pkg.apk").readBytes())
        assertEquals(Stage.PLACED, store.stage)
    }

    @Test fun rotationStoppedAfterStagingResumesWithoutResending() {
        val r = remote()
        val name = appFolderName()
        val v1 = apk(200_000)
        publisher(r).publish(request(r, name, 1, v1), MemoryStageStore()) { _, _ -> }

        // Upload v2 to its staging name by hand, as if the app died right after.
        val v2 = apk(210_000)
        val client = CopypartyClient(r, http)
        Up2kUploader(client, r.maxRequestBytes).upload(r.folder(pkg, name), Naming.stagingName(2, "$pkg.apk"),
            v2, Up2k.hashFile(v2), 1_700_000_002) { _, _ -> }
        proxy.reset()

        publisher(r).publish(request(r, name, 2, v2, previous = PreviousVersion("1.1", 1)), MemoryStageStore(Stage.STAGED)) { _, _ -> }
        assertEquals("no chunk sent twice", 0, proxy.chunkPosts.get())
        val dir = disk("apks", name)
        assertArrayEquals(v2.readBytes(), File(dir, "$pkg.apk").readBytes())
        assertArrayEquals(v1.readBytes(), File(dir, "older/${pkg}_1.1_1.apk").readBytes())
        assertTrue(dir.listFiles()!!.none { it.name.startsWith(".incoming-") })
    }

    @Test fun rotationStoppedAfterPlacingIsNotRepeated() {
        val r = remote()
        val name = appFolderName()
        val v1 = apk(100_000)
        val v2 = apk(100_001)
        publisher(r).publish(request(r, name, 1, v1), MemoryStageStore()) { _, _ -> }
        publisher(r).publish(request(r, name, 2, v2, previous = PreviousVersion("1.1", 1)), MemoryStageStore()) { _, _ -> }
        proxy.reset()
        // The app died after the move but before saving. The retry must not archive v2 over itself.
        val res = publisher(r).publish(request(r, name, 2, v2, previous = PreviousVersion("1.1", 1)), MemoryStageStore(Stage.PLACED)) { _, _ -> }
        assertEquals(0, proxy.chunkPosts.get())
        assertEquals(emptyList<Archived>(), res.archived)
        assertEquals(listOf("${pkg}_1.1_1.apk"), disk("apks", name).resolve("older").list()!!.toList())
    }

    @Test fun chunkAboveTheCapGoesInPieces() {
        val cap = 256L * 1024
        proxy.capBytes = cap
        val name = appFolderName()
        val f = apk(3 * 1024 * 1024) // three 1 MiB chunks

        // Control: without pieces the proxy rejects the chunk, so the cap is real.
        val big = remote(maxRequest = RemoteConfig.mib(8))
        try {
            publisher(big).publish(request(big, name, 1, f), MemoryStageStore()) { _, _ -> }
            fail("the proxy should reject a 1 MiB request")
        } catch (e: CopypartyException) {
            assertEquals(413, e.code)
        }

        proxy.reset()
        proxy.capBytes = cap
        val r = remote(maxRequest = cap)
        publisher(r).publish(request(r, name, 1, f), MemoryStageStore()) { _, _ -> }
        assertTrue("largest body ${proxy.largestBody.get()} is within the cap", proxy.largestBody.get() <= cap)
        assertEquals("each 1 MiB chunk in four pieces", 12, proxy.subchunkPosts.get())
        assertArrayEquals(f.readBytes(), File(disk("apks", name), "$pkg.apk").readBytes())
    }

    @Test fun missingPermissionIsReportedByNameAndNothingIsWritten() {
        val r = remote("limited")
        val name = appFolderName()
        try {
            publisher(r).publish(request(r, name, 1, apk(1000)), MemoryStageStore()) { _, _ -> }
            fail("expected a missing permission")
        } catch (e: PermissionMissingException) {
            assertEquals(listOf("m", "d"), e.missing)
            assertEquals("Missing copyparty permission: m, d", e.message)
        }
        assertFalse(disk("limited", name).exists())
    }

    @Test fun missingDotIsOnlyAWarning() {
        val r = remote("nodot")
        val name = appFolderName()
        publishSeries(r, name, n = 2, keep = 3)
        val res = publisher(r).publish(request(r, name, 3, apk(5000), previous = PreviousVersion("1.2", 2)), MemoryStageStore()) { _, _ -> }
        assertTrue(res.warnings.any { "'.'" in it })
        assertEquals(2, disk("nodot", name).resolve("older").list()!!.size)
    }

    @Test fun wrongPasswordIsAnAuthFailure() {
        val r = remote(password = "wrong")
        try {
            publisher(r).publish(request(r, appFolderName(), 1, apk(1000)), MemoryStageStore()) { _, _ -> }
            fail("expected an auth failure")
        } catch (e: AuthFailedException) {
            // expected
        }
    }

    @Test fun switchingFromSingleToSplitArchivesWithItsOwnExtension() {
        val r = remote()
        val name = appFolderName()
        publisher(r).publish(request(r, name, 1, apk(4000)), MemoryStageStore()) { _, _ -> }
        val bundle = tmp.newFile("v2.apks").apply { writeBytes(Random(2).nextBytes(9000)) }
        publisher(r).publish(request(r, name, 2, bundle, previous = PreviousVersion("1.1", 1), ext = "apks"), MemoryStageStore()) { _, _ -> }
        val dir = disk("apks", name)
        assertTrue(File(dir, "$pkg.apks").exists())
        assertFalse(File(dir, "$pkg.apk").exists())
        assertEquals(listOf("${pkg}_1.1_1.apk"), dir.resolve("older").list()!!.toList())
    }

    @Test fun forcedResendOfTheCurrentVersionChangesNothing() {
        val r = remote()
        val name = appFolderName()
        val v1 = apk(50_000)
        publisher(r).publish(request(r, name, 1, v1), MemoryStageStore()) { _, _ -> }
        proxy.reset()
        val res = publisher(r).publish(request(r, name, 1, v1, previous = PreviousVersion("1.1", 1)), MemoryStageStore()) { _, _ -> }
        assertTrue(res.alreadyPresent)
        assertEquals(0, proxy.chunkPosts.get())
        assertFalse(File(disk("apks", name), "older").exists())
    }

    @Test fun testConnectionReportsPermissions() {
        val full = publisher(remote()).testConnection()
        assertTrue(full.read && full.write && full.move && full.delete && full.dot)
        assertTrue("search mode works with -e2d", full.hashVerify)
        assertEquals(emptyList<String>(), full.missing())
        assertTrue(server.dirs.getValue("apks").list()!!.none { it.contains("offramp-test") })

        val limited = publisher(remote("limited")).testConnection()
        assertTrue(limited.read && limited.write)
        assertFalse(limited.move)
        assertFalse(limited.delete)
        assertEquals(listOf("m", "d", "."), limited.missing())
    }

    @Test fun testRequestSizePassesUnderTheCapAndFailsOverIt() {
        proxy.capBytes = 1024L * 1024
        publisher(remote(maxRequest = 1024L * 1024)).testRequestSize()
        try {
            publisher(remote(maxRequest = 2L * 1024 * 1024)).testRequestSize()
            fail("2 MiB should not pass a 1 MiB cap")
        } catch (e: CopypartyException) {
            assertEquals(413, e.code)
        }
    }
}
