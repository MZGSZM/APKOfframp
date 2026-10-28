package com.mzgszm.apkofframp.core

/** A kept version's place on the server, as the app records it. */
data class KeptLink(val id: Long, val remotePath: String, val url: String, val current: Boolean, val pruned: Boolean)

data class LinkUpdate(val id: Long, val remotePath: String, val url: String, val current: Boolean, val pruned: Boolean)

/**
 * Works out how the recorded links change after a publish:
 * the old current versions move into older/ (their link follows the file),
 * pruned versions keep their history entry but lose their link,
 * and the new upload becomes the only current version.
 */
object Ledger {
    fun afterPublish(remote: RemoteConfig, folder: List<String>, existing: List<KeptLink>, result: PublishResult): List<LinkUpdate> {
        val moved = result.archived.associate { a ->
            Naming.vpath(folder + a.fromName) to a.toPath
        }
        val pruned = result.pruned.map { Naming.vpath(it) }.toSet()
        val out = mutableListOf<LinkUpdate>()
        for (k in existing) {
            if (k.pruned) continue
            var path = k.remotePath
            var url = k.url
            var current = k.current
            if (k.current) {
                current = false
                moved[k.remotePath]?.let { to ->
                    path = Naming.vpath(to)
                    url = remote.url(to)
                }
            }
            val isPruned = path in pruned
            if (isPruned) url = ""
            if (path != k.remotePath || url != k.url || current != k.current || isPruned) {
                out += LinkUpdate(k.id, path, url, current, isPruned)
            }
        }
        return out
    }
}
