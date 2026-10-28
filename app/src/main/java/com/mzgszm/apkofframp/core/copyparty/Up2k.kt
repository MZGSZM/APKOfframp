package com.mzgszm.apkofframp.core.copyparty

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

data class Chunk(val hash: String, val offset: Long, val length: Long)

/**
 * The hashing half of copyparty's up2k protocol, ported from u2c.py 2.25.
 * Chunk sizing and hashing must match the server exactly or every chunk is rejected.
 */
object Up2k {
    /** Same as up2k_chunksize in u2c.py: start at 1 MiB, grow until the file fits in few enough chunks. */
    fun chunkSize(fileSize: Long): Long {
        var chunk = 1L shl 20
        var step = 512L shl 10
        var doubleNext = false
        while (!fits(fileSize, chunk)) {
            chunk += step
            if (doubleNext) step *= 2
            doubleNext = !doubleNext
        }
        return chunk
    }

    private fun fits(fileSize: Long, chunk: Long): Boolean {
        val n = (fileSize + chunk - 1) / chunk
        return n <= 256 || (chunk >= 32L shl 20 && n <= 4096)
    }

    /** SHA-512 of each chunk, first 33 bytes, URL-safe Base64. */
    fun hashFile(file: File): List<Chunk> {
        val size = file.length()
        val chunkSize = chunkSize(size)
        val out = ArrayList<Chunk>()
        val buf = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            var offset = 0L
            while (offset < size) {
                val len = minOf(chunkSize, size - offset)
                val md = MessageDigest.getInstance("SHA-512")
                var left = len
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                    if (n < 0) throw IOException("Unexpected end of file at ${offset + len - left}")
                    md.update(buf, 0, n)
                    left -= n
                }
                out += Chunk(encode(md.digest()), offset, len)
                offset += len
            }
        }
        return out
    }

    fun encode(digest: ByteArray): String = Base64.getUrlEncoder().encodeToString(digest.copyOf(33))
}

/**
 * The upload half: handshake, send the chunks the server still needs, handshake again.
 * An interrupted upload resumes by calling [upload] again; the server only asks for what is missing.
 */
class Up2kUploader(private val client: CopypartyClient, private val maxRequestBytes: Long) {

    data class Result(val name: String, val purl: String)

    fun upload(
        dir: List<String>,
        name: String,
        file: File,
        chunks: List<Chunk>,
        lmodSeconds: Long,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): Result {
        val total = file.length()
        var passes = 0
        var rechecks = 0
        while (true) {
            val hs = try {
                client.handshake(dir, name, chunks.map { it.hash }, total, lmodSeconds)
            } catch (e: RecheckException) {
                if (++rechecks > 5) throw e
                Thread.sleep(2000L * rechecks)
                continue
            }
            val needed = hs.hash.toSet()
            if (needed.isEmpty()) {
                onProgress(total, total)
                return Result(hs.name, hs.purl)
            }
            if (++passes > 4) throw IOException("Upload of $name still incomplete after $passes passes")

            // Identical chunks share a hash; the server wants each hash once.
            val todo = chunks.filter { it.hash in needed }.distinctBy { it.hash }
            var sent = total - chunks.filter { it.hash in needed }.sumOf { it.length }
            onProgress(sent, total)
            for (c in todo) {
                client.putChunk(hs.purl, hs.wark, c.hash, file, c.offset, c.length, maxRequestBytes) { n ->
                    sent += n
                    onProgress(sent, total)
                }
            }
        }
    }
}
