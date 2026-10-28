package com.mzgszm.apkofframp.core.copyparty

import com.mzgszm.apkofframp.core.Naming
import com.mzgszm.apkofframp.core.RemoteConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder

@Serializable
data class LsFile(val href: String = "", val sz: Long = 0, val ts: Long = 0)

@Serializable
data class LsDir(val href: String = "")

@Serializable
data class LsReply(
    val dirs: List<LsDir> = emptyList(),
    val files: List<LsFile> = emptyList(),
    val perms: List<String> = emptyList(),
)

@Serializable
data class HandshakeReply(
    val name: String = "",
    val purl: String = "",
    val wark: String = "",
    val hash: List<String> = emptyList(),
)

@Serializable
data class SearchHit(val rp: String = "", val sz: Long = 0)

@Serializable
data class SearchReply(val hits: List<SearchHit> = emptyList())

data class RemoteFile(val name: String, val size: Long, val modified: Long)

/** A folder listing with decoded names. `perms` uses copyparty's words: read, write, move, delete, dot. */
data class Listing(val perms: Set<String>, val files: Map<String, RemoteFile>, val dirs: Set<String>)

/**
 * The parts of copyparty's HTTP API that Offramp uses. All calls block, so run them on an IO thread.
 * Paths are lists of decoded segments; this class does the encoding.
 */
class CopypartyClient(private val remote: RemoteConfig, private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun request(url: String): Request.Builder {
        val b = Request.Builder().url(url)
        remote.password?.takeIf { it.isNotEmpty() }?.let { b.header("PW", it) }
        return b
    }

    private fun call(req: Request): Pair<Int, String> =
        http.newCall(req).execute().use { resp -> resp.code to resp.body.string() }

    private fun fail(code: Int, op: String, body: String): Nothing = throw CopypartyException(code, op, body)

    /** GET ?ls. Returns null when the folder does not exist. */
    fun ls(dir: List<String>, dots: Boolean): Listing? {
        val url = remote.url(dir, dir = true) + if (dots) "?ls&dots" else "?ls"
        val (code, body) = call(request(url).get().build())
        if (code == 404) return null
        if (code >= 400) fail(code, "List", body)
        val reply = json.decodeFromString(LsReply.serializer(), body)
        val files = reply.files.associate { f ->
            val name = decodeHref(f.href)
            name to RemoteFile(name, f.sz, f.ts)
        }
        return Listing(reply.perms.toSet(), files, reply.dirs.map { decodeHref(it.href) }.toSet())
    }

    fun mkdir(parent: List<String>, name: String) {
        val form = FormBody.Builder().add("act", "mkdir").add("name", name).build()
        val (code, body) = call(request(remote.url(parent, dir = true)).post(form).build())
        if (code >= 400) fail(code, "Create folder", body)
    }

    /** POST ?move. copyparty refuses to overwrite an existing target, which is what we want. */
    fun move(src: List<String>, dst: List<String>) {
        val url = remote.url(src) + "?move=" + Naming.encodeSegment(Naming.vpath(dst))
        val (code, body) = call(request(url).post(EMPTY).build())
        if (code >= 400) fail(code, "Move", body)
    }

    /** POST ?delete. Returns false when the file was already gone. */
    fun delete(path: List<String>): Boolean {
        val (code, body) = call(request(remote.url(path) + "?delete").post(EMPTY).build())
        if (code == 404) return false
        if (code >= 400) fail(code, "Delete", body)
        return true
    }

    /** Plain PUT upload of a throwaway file, used only by the request-size test. */
    fun putRaw(path: List<String>, size: Long) {
        val body = object : RequestBody() {
            override fun contentType() = OCTET
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val buf = ByteArray(64 * 1024)
                var left = size
                while (left > 0) {
                    val n = minOf(left, buf.size.toLong()).toInt()
                    sink.write(buf, 0, n)
                    left -= n
                }
            }
        }
        val (code, text) = call(request(remote.url(path)).put(body).build())
        if (code >= 400) fail(code, "Upload test file", text)
    }

    fun handshake(dir: List<String>, name: String, hashes: List<String>, size: Long, lmod: Long): HandshakeReply {
        val text = postHandshake(dir, name, hashes, size, lmod, search = false)
        return json.decodeFromString(HandshakeReply.serializer(), text)
    }

    /** Search-mode handshake: finds files on the server with these chunk hashes. Needs the folder indexed. */
    fun search(dir: List<String>, hashes: List<String>, size: Long): List<SearchHit> {
        val text = postHandshake(dir, "search", hashes, size, 0, search = true)
        return json.decodeFromString(SearchReply.serializer(), text).hits
    }

    private fun postHandshake(
        dir: List<String>, name: String, hashes: List<String>, size: Long, lmod: Long, search: Boolean,
    ): String {
        val payload = buildJsonObject {
            putJsonArray("hash") { hashes.forEach { add(it) } }
            put("name", name)
            put("lmod", lmod)
            put("size", size)
            if (search) put("srch", 1)
        }.toString()
        val req = request(remote.url(dir, dir = true)).post(payload.toRequestBody(JSON_TYPE)).build()
        val (code, body) = call(req)
        when {
            code < 400 -> return body
            code == 422 || "partial upload exists at a different" in body || "source file busy" in body ->
                throw RecheckException("Server asked to retry the handshake for $name")
            code == 409 || "upload rejected, file already exists" in body ->
                throw FileExistsException("$name already exists on the server")
            else -> fail(code, if (search) "Search" else "Handshake", body)
        }
    }

    /**
     * Sends one chunk. A chunk larger than [maxRequestBytes] goes in pieces, each tagged with
     * X-Up2k-Subc (its offset inside the chunk), so a proxy's request-size cap is respected.
     */
    fun putChunk(
        purl: String, wark: String, chunkHash: String, file: File,
        offset: Long, length: Long, maxRequestBytes: Long, onBytes: (Long) -> Unit,
    ) {
        val segs = purl.split('/').filter { it.isNotEmpty() }
        val url = remote.origin + Naming.encodePath(segs, dir = purl.endsWith("/"))
        var sub = 0L
        while (sub < length) {
            val n = if (length <= maxRequestBytes) length else minOf(maxRequestBytes, length - sub)
            val b = request(url)
                .header("X-Up2k-Hash", chunkHash)
                .header("X-Up2k-Wark", wark)
            if (length > maxRequestBytes) b.header("X-Up2k-Subc", sub.toString())
            val (code, body) = call(b.post(FileRangeBody(file, offset + sub, n, onBytes)).build())
            // "already got that" means another attempt delivered this chunk first.
            if (code >= 400 && !(code == 400 && "already got that" in body)) fail(code, "Upload chunk", body)
            sub += n
        }
    }

    private class FileRangeBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
        private val onBytes: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = OCTET
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                    if (n < 0) throw EOFException("File shrank during upload")
                    sink.write(buf, 0, n)
                    left -= n
                    onBytes(n.toLong())
                }
            }
        }
    }

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)

        fun decodeHref(href: String): String =
            URLDecoder.decode(href.replace("+", "%2B"), "UTF-8").trimEnd('/')
    }
}
