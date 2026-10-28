package com.mzgszm.apkofframp.core

import com.mzgszm.apkofframp.core.copyparty.AuthFailedException
import com.mzgszm.apkofframp.core.copyparty.Chunk
import com.mzgszm.apkofframp.core.copyparty.CopypartyClient
import com.mzgszm.apkofframp.core.copyparty.CopypartyException
import com.mzgszm.apkofframp.core.copyparty.Listing
import com.mzgszm.apkofframp.core.copyparty.PermissionMissingException
import com.mzgszm.apkofframp.core.copyparty.Up2k
import com.mzgszm.apkofframp.core.copyparty.Up2kUploader
import com.mzgszm.apkofframp.core.copyparty.VerifyFailedException
import java.io.File
import java.io.IOException
import java.net.URLDecoder

/** How far an upload of one version got. Saved between attempts so a retry knows where to look. */
enum class Stage { NONE, UPLOADING, STAGED, PLACED }

interface StageStore {
    fun load(): Stage
    fun save(stage: Stage)
}

class MemoryStageStore(var stage: Stage = Stage.NONE) : StageStore {
    override fun load() = stage
    override fun save(stage: Stage) { this.stage = stage }
}

data class PreviousVersion(val versionName: String?, val versionCode: Long)

data class PublishRequest(
    val pkg: String,
    val versionCode: Long,
    val versionName: String?,
    val file: File,
    val ext: String,
    val folder: List<String>,
    val keep: Int,
    val previous: PreviousVersion?,
    val lmodSeconds: Long,
    val hashVerify: Boolean,
)

data class Archived(val fromName: String, val toPath: List<String>)

data class PublishResult(
    val currentPath: List<String>,
    val archived: List<Archived>,
    val pruned: List<List<String>>,
    val warnings: List<String>,
    val alreadyPresent: Boolean,
    val verifiedBy: String,
)

data class TestReport(
    val read: Boolean,
    val write: Boolean,
    val move: Boolean,
    val delete: Boolean,
    val dot: Boolean,
    val hashVerify: Boolean,
    val notes: List<String>,
) {
    /** Copyparty letters for every permission that failed. */
    fun missing(): List<String> = buildList {
        if (!read) add("r")
        if (!write) add("w")
        if (!move) add("m")
        if (!delete) add("d")
        if (!dot) add(".")
    }
}

/**
 * Uploads one version and rotates the folder:
 * stage under a hidden name, archive the current file into older/, move the new file
 * into place, prune old versions. Nothing visible changes until the staged file is complete.
 */
class Publisher(private val client: CopypartyClient, private val remote: RemoteConfig) {

    /** Checks the permissions rotation needs and returns copyparty's permission words. */
    fun checkPermissions(folder: List<String>, keep: Int): Set<String> {
        val listing = try {
            nearestListing(folder)
        } catch (e: CopypartyException) {
            if (e.code == 401 || e.code == 403) {
                throw AuthFailedException("Login failed or no read access. Check the password and the account's permissions.")
            }
            throw e
        }
        val perms = listing.perms
        val required = buildList {
            add("read" to "r")
            add("write" to "w")
            add("move" to "m")
            if (keep != Naming.KEEP_ALL) add("delete" to "d")
        }
        val missing = required.filter { it.first !in perms }.map { it.second }
        if (missing.isNotEmpty()) throw PermissionMissingException(missing)
        return perms
    }

    private fun nearestListing(folder: List<String>): Listing {
        var segs = folder
        while (true) {
            client.ls(segs, dots = false)?.let { return it }
            if (segs.isEmpty()) throw CopypartyException(404, "List", "destination not found")
            segs = segs.dropLast(1)
        }
    }

    fun ensureFolder(folder: List<String>) {
        if (folder.isEmpty() || client.ls(folder, dots = false) != null) return
        val parent = folder.dropLast(1)
        ensureFolder(parent)
        client.mkdir(parent, folder.last())
    }

    fun publish(req: PublishRequest, store: StageStore, onProgress: (Long, Long) -> Unit): PublishResult {
        val warnings = mutableListOf<String>()
        val perms = checkPermissions(req.folder, req.keep)
        val dots = "dot" in perms
        if (!dots) warnings += "No '.' permission: an interrupted rotation uploads again instead of resuming."

        val chunks = Up2k.hashFile(req.file)
        val size = req.file.length()
        val stable = Naming.stableName(req.pkg, req.ext)
        val stablePath = req.folder + stable
        ensureFolder(req.folder)

        var stage = store.load()

        // A forced re-send of the version that is already current: nothing to do if the server has it.
        if (stage == Stage.NONE && req.previous?.versionCode == req.versionCode && req.hashVerify &&
            isOurs(req.folder, stable, chunks, size, trustSize = false)
        ) {
            prune(req)
            return PublishResult(stablePath, emptyList(), emptyList(), warnings, alreadyPresent = true, verifiedBy = "hash")
        }

        // If an earlier attempt got past staging, the new file may already be in place.
        if (stage == Stage.STAGED || stage == Stage.PLACED) {
            stage = if (isOurs(req.folder, stable, chunks, size, trustSize = stage == Stage.PLACED)) {
                Stage.PLACED
            } else {
                Stage.NONE
            }
        }

        val archived = mutableListOf<Archived>()
        var verifyFailures = 0
        var placeAttempts = 0
        while (stage != Stage.PLACED) {
            // 1. Upload under the hidden staging name. A complete staged file costs one handshake.
            store.save(Stage.UPLOADING)
            var staging = Naming.stagingName(req.versionCode, stable)
            val res = Up2kUploader(client, remote.maxRequestBytes)
                .upload(req.folder, staging, req.file, chunks, req.lmodSeconds, onProgress)
            if (res.name != staging) {
                // The server renamed it because a different file had the staging name. Use the new name.
                staging = res.name
            }
            // The final handshake returning no missing chunks means the server verified every chunk hash.
            if (dots) {
                val seen = client.ls(req.folder, dots = true)?.files?.get(staging)
                if (seen == null || seen.size != size) {
                    client.delete(req.folder + staging)
                    if (++verifyFailures >= 3) throw VerifyFailedException("Staged file did not match after 3 attempts")
                    continue
                }
            }
            store.save(Stage.STAGED)

            // 2-3. Archive whatever current file is there, with its own extension.
            archived += archiveCurrent(req)

            // 4. Move the staged file to its stable name.
            try {
                client.move(req.folder + staging, stablePath)
            } catch (e: CopypartyException) {
                if (e.code == 404 && ++placeAttempts < 3) {
                    // Staged file vanished (without '.' we could not see it). Upload again.
                    store.save(Stage.NONE)
                    continue
                }
                throw e
            }
            stage = Stage.PLACED
            store.save(Stage.PLACED)
        }

        // Confirm the file sits at the expected path.
        val verifiedBy = verifyFinal(req, stable, chunks, size, warnings)

        // 5. Prune, and clear out staging leftovers from older attempts.
        val pruned = prune(req)
        cleanupStaging(req, dots)

        return PublishResult(stablePath, archived, pruned, warnings, alreadyPresent = false, verifiedBy = verifiedBy)
    }

    private fun isOurs(folder: List<String>, name: String, chunks: List<Chunk>, size: Long, trustSize: Boolean): Boolean {
        val f = client.ls(folder, dots = false)?.files?.get(name) ?: return false
        if (f.size != size) return false
        return try {
            searchFinds(folder, name, chunks, size)
        } catch (e: IOException) {
            trustSize
        }
    }

    private fun searchFinds(folder: List<String>, name: String, chunks: List<Chunk>, size: Long): Boolean {
        val want = (folder + name).joinToString("/")
        return client.search(folder, chunks.map { it.hash }, size).any { hit ->
            val rp = hit.rp.trim('/')
            rp == want || runCatching { URLDecoder.decode(rp, "UTF-8") }.getOrNull() == want
        }
    }

    private fun archiveCurrent(req: PublishRequest): List<Archived> {
        val listing = client.ls(req.folder, dots = false) ?: return emptyList()
        val present = Naming.stableCandidates(req.pkg).filter { it in listing.files }
        if (present.isEmpty()) return emptyList()

        val older = req.folder + "older"
        if ("older" !in listing.dirs && client.ls(older, dots = false) == null) client.mkdir(req.folder, "older")
        val existing = client.ls(older, dots = false)?.files?.keys ?: emptySet()

        return present.map { name ->
            val ext = Naming.extensionOf(name)
            val prevName = req.previous?.versionName
            val prevCode = req.previous?.versionCode ?: 0L
            var dup = 0
            var target = Naming.archiveName(req.pkg, prevName, prevCode, ext)
            while (target in existing) {
                dup++
                target = Naming.archiveName(req.pkg, prevName, prevCode, ext, dup)
            }
            client.move(req.folder + name, older + target)
            Archived(name, older + target)
        }
    }

    private fun verifyFinal(req: PublishRequest, stable: String, chunks: List<Chunk>, size: Long, warnings: MutableList<String>): String {
        if (req.hashVerify) {
            val found = try {
                searchFinds(req.folder, stable, chunks, size)
            } catch (e: IOException) {
                false
            }
            if (found) return "hash"
            warnings += "Hash search did not find the file; checked its size instead."
        }
        val f = client.ls(req.folder, dots = false)?.files?.get(stable)
            ?: throw VerifyFailedException("$stable is not in the folder after the move")
        if (f.size != size) throw VerifyFailedException("$stable is ${f.size} bytes on the server, expected $size")
        return "size"
    }

    /** Deletes the oldest archived versions beyond the versions-kept count. Age comes from the version code. */
    fun prune(req: PublishRequest): List<List<String>> {
        if (req.keep == Naming.KEEP_ALL) return emptyList()
        val older = req.folder + "older"
        val files = client.ls(older, dots = false)?.files?.keys ?: return emptyList()
        val ours = files.mapNotNull { name -> Naming.versionCodeFromArchive(req.pkg, name)?.let { name to it } }
        val doomed = ours
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenByDescending { it.first })
            .drop(maxOf(0, req.keep))
        return doomed.map { (name, _) ->
            client.delete(older + name)
            older + name
        }
    }

    private fun cleanupStaging(req: PublishRequest, dots: Boolean) {
        if (!dots || req.keep == Naming.KEEP_ALL) return
        val files = client.ls(req.folder, dots = true)?.files?.keys ?: return
        for (name in files) {
            if (Naming.isStagingFor(name, req.pkg)) {
                runCatching { client.delete(req.folder + name) }
            }
        }
    }

    /** Writes a marker, lists it, moves it, searches for it, deletes it. Reports what worked. */
    fun testConnection(): TestReport {
        val notes = mutableListOf<String>()
        val base = remote.baseSegments
        val listing = try {
            client.ls(base, dots = false)
        } catch (e: CopypartyException) {
            if (e.code == 401 || e.code == 403) {
                throw AuthFailedException("Login failed or no read access. Check the password.")
            }
            throw e
        } ?: throw IOException("The destination folder does not exist on the server")
        val perms = listing.perms
        notes += "Server reports: " + perms.joinToString(", ").ifEmpty { "no permissions" }

        val stamp = System.currentTimeMillis()
        val hidden = ".offramp-test-$stamp.txt"
        val visible = "offramp-test-$stamp.txt"
        val tmp = File.createTempFile("offramp-test", ".txt")
        tmp.writeText("APK Offramp connection test $stamp\n")
        val chunks = Up2k.hashFile(tmp)

        var write = false
        var dot = false
        var move = false
        var hashVerify = false
        var delete = false
        var current: String? = null
        try {
            try {
                Up2kUploader(client, remote.maxRequestBytes).upload(base, hidden, tmp, chunks, stamp / 1000) { _, _ -> }
                write = true
                current = hidden
            } catch (e: IOException) {
                notes += "Write: ${e.message}"
            }
            if (write) {
                dot = try {
                    client.ls(base, dots = true)?.files?.containsKey(hidden) == true
                } catch (e: IOException) {
                    false
                }
                if (!dot) notes += "Hidden files are not listed, so an interrupted rotation uploads again instead of resuming."
                try {
                    client.move(base + hidden, base + visible)
                    move = true
                    current = visible
                } catch (e: IOException) {
                    notes += "Move: ${e.message}"
                }
                if (move) {
                    hashVerify = try {
                        searchFinds(base, visible, chunks, tmp.length())
                    } catch (e: IOException) {
                        false
                    }
                    if (!hashVerify) notes += "Hash search found nothing, so uploads are checked by size. Index the folder (-e2d) to enable hash checks."
                }
                try {
                    current?.let { client.delete(base + it) }
                    delete = true
                    current = null
                } catch (e: IOException) {
                    notes += "Delete: ${e.message}"
                }
            }
        } finally {
            tmp.delete()
        }
        if (current != null) notes += "Left a test file behind: $current"
        return TestReport(read = true, write = write, move = move, delete = delete, dot = dot, hashVerify = hashVerify, notes = notes)
    }

    /** Uploads and deletes a throwaway file of the maximum request size in one request. */
    fun testRequestSize() {
        val path = remote.baseSegments + ".offramp-size-test-${System.currentTimeMillis()}.bin"
        client.putRaw(path, remote.maxRequestBytes)
        client.delete(path)
    }
}
