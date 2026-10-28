package com.mzgszm.apkofframp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kept-version links: the current link is recorded, rotation moves it, pruning removes it but keeps history. */
class LedgerTest {
    private val remote = RemoteConfig("https://h/apks/", "pw", RemoteConfig.mib(32), false)
    private val folder = remote.folder("com.x.y", null)

    private fun result(archived: List<Archived> = emptyList(), pruned: List<List<String>> = emptyList()) =
        PublishResult(folder + "com.x.y.apk", archived, pruned, emptyList(), alreadyPresent = false, verifiedBy = "hash")

    @Test fun firstUploadChangesNothingElse() {
        assertEquals(emptyList<LinkUpdate>(), Ledger.afterPublish(remote, folder, emptyList(), result()))
    }

    @Test fun rotationMovesTheOldLinkIntoOlder() {
        val existing = listOf(KeptLink(1, "/apks/com.x.y/com.x.y.apk", "https://h/apks/com.x.y/com.x.y.apk", current = true, pruned = false))
        val archivedTo = folder + "older" + "com.x.y_1.0 b_10.apk"
        val updates = Ledger.afterPublish(remote, folder, existing, result(listOf(Archived("com.x.y.apk", archivedTo))))
        assertEquals(1, updates.size)
        val u = updates.single()
        assertEquals(1L, u.id)
        assertEquals("/apks/com.x.y/older/com.x.y_1.0 b_10.apk", u.remotePath)
        assertEquals("https://h/apks/com.x.y/older/com.x.y_1.0%20b_10.apk", u.url)
        assertEquals(false, u.current)
        assertEquals(false, u.pruned)
    }

    @Test fun pruningRemovesTheLinkButKeepsTheEntry() {
        val existing = listOf(
            KeptLink(1, "/apks/com.x.y/com.x.y.apk", "https://h/apks/com.x.y/com.x.y.apk", current = true, pruned = false),
            KeptLink(2, "/apks/com.x.y/older/com.x.y_0.9_9.apk", "https://h/apks/com.x.y/older/com.x.y_0.9_9.apk", current = false, pruned = false),
            KeptLink(3, "/apks/com.x.y/older/com.x.y_0.8_8.apk", "https://h/apks/com.x.y/older/com.x.y_0.8_8.apk", current = false, pruned = false),
        )
        val updates = Ledger.afterPublish(
            remote, folder, existing,
            result(
                archived = listOf(Archived("com.x.y.apk", folder + "older" + "com.x.y_1.0_10.apk")),
                pruned = listOf(folder + "older" + "com.x.y_0.8_8.apk"),
            ),
        ).associateBy { it.id }
        assertEquals(setOf(1L, 3L), updates.keys)
        assertEquals("https://h/apks/com.x.y/older/com.x.y_1.0_10.apk", updates.getValue(1).url)
        assertTrue(updates.getValue(3).pruned)
        assertEquals("", updates.getValue(3).url)
        assertEquals("/apks/com.x.y/older/com.x.y_0.8_8.apk", updates.getValue(3).remotePath)
    }

    @Test fun switchFromSingleToSplitArchivesWithOwnExtension() {
        val existing = listOf(KeptLink(1, "/apks/com.x.y/com.x.y.apk", "https://h/apks/com.x.y/com.x.y.apk", current = true, pruned = false))
        val res = PublishResult(folder + "com.x.y.apks", listOf(Archived("com.x.y.apk", folder + "older" + "com.x.y_1.0_10.apk")),
            emptyList(), emptyList(), alreadyPresent = false, verifiedBy = "size")
        val u = Ledger.afterPublish(remote, folder, existing, res).single()
        assertTrue(u.url.endsWith("/older/com.x.y_1.0_10.apk"))
    }

    @Test fun linksNeverCarryThePassword() {
        val existing = listOf(KeptLink(1, "/apks/com.x.y/com.x.y.apk", "https://h/apks/com.x.y/com.x.y.apk", current = true, pruned = false))
        val u = Ledger.afterPublish(remote, folder, existing, result(listOf(Archived("com.x.y.apk", folder + "older" + "a.apk")))).single()
        assertTrue(!u.url.contains("pw"))
    }
}
