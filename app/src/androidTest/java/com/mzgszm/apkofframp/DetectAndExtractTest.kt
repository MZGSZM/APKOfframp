package com.mzgszm.apkofframp

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mzgszm.apkofframp.core.Naming
import com.mzgszm.apkofframp.data.Settings
import com.mzgszm.apkofframp.system.Extractor
import com.mzgszm.apkofframp.system.InstalledApps
import com.mzgszm.apkofframp.work.Work
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * A test APK installed, updated, detected, then extracted and verified.
 * The emulator workflow pushes /data/local/tmp/offramp-testapp-v1.apk and -v2.apk (built from :testapp).
 */
@RunWith(AndroidJUnit4::class)
class DetectAndExtractTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val apps = InstalledApps(ctx)
    private val extractor = Extractor(ctx, apps)
    private val testPkg = "com.mzgszm.offramptest"

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    @After fun cleanup() {
        shell("pm uninstall $testPkg")
    }

    @Test fun installUpdateDetectExtract() {
        val v1 = "/data/local/tmp/offramp-testapp-v1.apk"
        val v2 = "/data/local/tmp/offramp-testapp-v2.apk"
        assumeTrue("test APKs not pushed", shell("ls $v1 $v2").contains("v2.apk"))

        shell("pm uninstall $testPkg")
        assertTrue(shell("pm install -r $v1").contains("Success"))
        val first = apps.snapshot(testPkg)
        assertNotNull(first)
        assertEquals(1L, first!!.versionCode)
        assertTrue(Naming.shouldUpload(first.versionCode, null))

        // Pretend version 1 was uploaded; nothing new to do.
        assertFalse(Naming.shouldUpload(apps.snapshot(testPkg)!!.versionCode, 1))

        assertTrue(shell("pm install -r $v2").contains("Success"))
        val second = apps.snapshot(testPkg)!!
        assertEquals(2L, second.versionCode)
        assertTrue("an update is detected", Naming.shouldUpload(second.versionCode, 1))

        val ex = extractor.extract(second)
        assertEquals("apk", ex.ext)
        assertTrue(File(second.sourceDir).readBytes().contentEquals(ex.upload.readBytes()))
        assertEquals(sha256(ex.upload), ex.sha256)
        assertEquals(testPkg to 2L, apps.archiveInfo(ex.upload.path))
        assertNotNull(ex.certDigest)
        extractor.cleanup(testPkg, 2)
        assertFalse(ex.dir.exists())
    }

    @Test fun extractOwnInstallAndVerify() {
        // Always available: the app under test itself. Split when installed from an app bundle, single otherwise.
        val snap = apps.snapshot(ctx.packageName)!!
        val ex = extractor.extract(snap)
        assertEquals(ctx.packageName to snap.versionCode, apps.archiveInfo(File(ex.dir, File(snap.sourceDir).name).path))
        if (snap.isSplit) {
            ZipFile(ex.upload).use { z ->
                val names = z.entries().toList().map { it.name }
                assertEquals(File(snap.sourceDir).name, names.first())
                assertEquals(1 + snap.splitSourceDirs.size, names.size)
            }
        }
        extractor.cleanup(ctx.packageName, snap.versionCode)
    }

    @Test fun sweepAndInstantModeCannotQueueTheSameVersionTwice() {
        val pkg = "com.example.duplicate.test"
        val wm = WorkManager.getInstance(ctx)
        val s = Settings(wifiOnly = false, chargingOnly = false)
        Work.enqueueUpload(ctx, pkg, 42, force = false, settings = s)
        Work.enqueueUpload(ctx, pkg, 42, force = false, settings = s)
        val infos = wm.getWorkInfosForUniqueWork(Naming.uploadWorkName(pkg, 42)).get()
        assertEquals(1, infos.size)
        Work.enqueueUpload(ctx, pkg, 43, force = false, settings = s)
        assertEquals(1, wm.getWorkInfosForUniqueWork(Naming.uploadWorkName(pkg, 43)).get().size)
        Work.cancelFor(ctx, pkg)
        val states = wm.getWorkInfosByTag(Work.tagFor(pkg)).get().map { it.state }
        assertTrue(states.all { it.isFinished || it == WorkInfo.State.CANCELLED })
    }

    private fun sha256(f: File) =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
}
