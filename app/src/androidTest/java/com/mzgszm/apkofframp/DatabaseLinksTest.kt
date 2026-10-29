package com.mzgszm.apkofframp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzgszm.apkofframp.core.Archived
import com.mzgszm.apkofframp.core.KeptLink
import com.mzgszm.apkofframp.core.Ledger
import com.mzgszm.apkofframp.core.PublishResult
import com.mzgszm.apkofframp.core.RemoteConfig
import com.mzgszm.apkofframp.data.AppEntity
import com.mzgszm.apkofframp.data.DestinationEntity
import com.mzgszm.apkofframp.data.OfframpDb
import com.mzgszm.apkofframp.data.UploadEntity
import com.mzgszm.apkofframp.data.VersionEntity
import com.mzgszm.apkofframp.data.VersionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Kept-version links through Room: record, move into older/, prune but keep the entry. */
@RunWith(AndroidJUnit4::class)
class DatabaseLinksTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(ctx, OfframpDb::class.java).build()
    private val dao = db.dao()
    private val remote = RemoteConfig("https://h/apks/", null, RemoteConfig.mib(32), false)
    private val pkg = "com.x.y"
    private val folder = remote.folder(pkg, null)

    @After fun close() = db.close()

    private suspend fun publish(code: Long, archived: List<Archived>, pruned: List<List<String>>) {
        val live = dao.liveVersions(pkg)
        val result = PublishResult(folder + "$pkg.apk", archived, pruned, emptyList(), false, "hash")
        val updates = Ledger.afterPublish(remote, folder, live.map {
            KeptLink(it.id, it.remotePath, it.url, it.state == VersionState.CURRENT, false)
        }, result)
        val upload = UploadEntity(workId = "w$code", packageName = pkg, displayName = "X", versionCode = code,
            versionName = "1.$code", startTime = code)
        val up = upload.copy(id = dao.insertUpload(upload))
        dao.recordPublished(
            AppEntity(pkg, "X", lastVersionCode = code, lastVersionName = "1.$code", lastUploadTime = code),
            updates,
            VersionEntity(packageName = pkg, versionCode = code, versionName = "1.$code", size = 1, sha256 = "x",
                uploadTime = code, destinationId = 1, state = VersionState.CURRENT,
                remotePath = "/apks/$pkg/$pkg.apk", url = "https://h/apks/$pkg/$pkg.apk"),
            up,
        )
    }

    @Test fun linksFollowRotationAndPruning() = runBlocking {
        publish(1, emptyList(), emptyList())
        var v = dao.observeVersions(pkg).first()
        assertEquals(1, v.size)
        assertEquals("https://h/apks/$pkg/$pkg.apk", v.single().url)

        publish(2, listOf(Archived("$pkg.apk", folder + "older" + "${pkg}_1.1_1.apk")), emptyList())
        v = dao.observeVersions(pkg).first()
        assertEquals(listOf(VersionState.CURRENT, VersionState.ARCHIVED), v.map { it.state })
        assertEquals("https://h/apks/$pkg/older/${pkg}_1.1_1.apk", v[1].url)

        publish(3, listOf(Archived("$pkg.apk", folder + "older" + "${pkg}_1.2_2.apk")), listOf(folder + "older" + "${pkg}_1.1_1.apk"))
        v = dao.observeVersions(pkg).first()
        assertEquals(listOf(3L, 2L, 1L), v.map { it.versionCode })
        assertEquals(listOf(VersionState.CURRENT, VersionState.ARCHIVED, VersionState.PRUNED), v.map { it.state })
        assertEquals("", v[2].url)
        assertEquals(3, dao.observeHistoryFor(pkg).first().size)
    }

    /** A destination picked while an upload ran must survive the upload being recorded. */
    @Test fun recordingAnUploadKeepsTheChosenDestination() = runBlocking {
        val second = dao.saveDestination(DestinationEntity(name = "Second", baseUrl = "https://second/apks/"))
        dao.saveDestination(DestinationEntity(name = "First", baseUrl = "https://h/apks/", isDefault = true))
        dao.upsertApp(AppEntity(pkg, "X"))
        // The worker read the row here, before the user changed the destination.
        val staleRow = dao.app(pkg)!!
        dao.setAppSettings(pkg, second, "custom", 5)

        val upload = UploadEntity(workId = "w9", packageName = pkg, displayName = "X", versionCode = 9, versionName = "9", startTime = 9)
        dao.recordPublished(
            staleRow.copy(lastVersionCode = 9, lastVersionName = "9", lastUploadTime = 9),
            emptyList(), null, upload.copy(id = dao.insertUpload(upload)),
        )

        val row = dao.app(pkg)!!
        assertEquals(second, row.destinationId)
        assertEquals("custom", row.pathOverride)
        assertEquals(5, row.keepVersions)
        assertEquals(9L, row.lastVersionCode)
    }

    @Test fun theDefaultCannotBeSwitchedOffDirectly() = runBlocking {
        val a = dao.saveDestination(DestinationEntity(name = "A", baseUrl = "https://a/apks/"))
        dao.saveDestination(DestinationEntity(name = "B", baseUrl = "https://b/apks/"))
        dao.saveDestination(dao.destination(a)!!.copy(isDefault = false))
        assertEquals(a, dao.defaultDestination()?.id)
    }
}
