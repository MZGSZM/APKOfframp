package com.mzgszm.apkofframp.core

import com.sun.net.httpserver.HttpServer
import org.junit.Assume
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A throwaway copyparty on a free port, with four volumes:
 *   /apks     rwmd.  everything
 *   /nodot    rwmd   no dotfile listing
 *   /limited  rw     no move, no delete
 *   /keepall  rwm.   no delete
 * Account "zack", password "hunter". Indexing (-e2d) is on so search-mode verification works.
 *
 * Needs Python with copyparty installed (pip install copyparty). Set COPYPARTY_PYTHON to pick the
 * interpreter. Without it the tests are skipped, unless REQUIRE_COPYPARTY=1, which makes them fail (CI).
 */
class CopypartyHarness(root: File) : AutoCloseable {
    val port = freePort()
    val dirs = listOf("apks", "nodot", "limited", "keepall").associateWith { File(root, it).apply { mkdirs() } }
    private val process: Process

    init {
        val python = System.getenv("COPYPARTY_PYTHON") ?: "python3"
        val available = runCatching {
            val p = ProcessBuilder(python, "-c", "import copyparty").redirectErrorStream(true).start()
            p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
        if (!available) {
            check(System.getenv("REQUIRE_COPYPARTY") != "1") { "copyparty is required but $python cannot import it" }
            Assume.assumeTrue("copyparty not installed; skipping integration tests", false)
        }
        val log = File(root, "copyparty.log")
        process = ProcessBuilder(
            python, "-m", "copyparty",
            "-i", "127.0.0.1", "-p", port.toString(),
            "-a", "zack:hunter",
            "-v", "${dirs["apks"]}:apks:rwmd.,zack",
            "-v", "${dirs["nodot"]}:nodot:rwmd,zack",
            "-v", "${dirs["limited"]}:limited:rw,zack",
            "-v", "${dirs["keepall"]}:keepall:rwm.,zack",
            "-e2d", "-q",
        ).redirectErrorStream(true).redirectOutput(log).start()
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) break
            check(process.isAlive) { "copyparty exited: ${log.readText().takeLast(2000)}" }
            check(System.currentTimeMillis() < deadline) { "copyparty did not start" }
            Thread.sleep(100)
        }
    }

    val baseUrl get() = "http://127.0.0.1:$port"

    override fun close() {
        process.destroy()
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}

/**
 * A forwarding proxy in front of copyparty, standing in for a reverse proxy with a request-size cap.
 * Counts chunk uploads, records the largest request body, and can fail chunk uploads after a count.
 */
class CapProxy(private val upstream: String, var capBytes: Long = Long.MAX_VALUE) : AutoCloseable {
    val port = CopypartyHarness.freePort()
    val chunkPosts = AtomicInteger()
    val subchunkPosts = AtomicInteger()
    val largestBody = AtomicLong()
    /** After this many chunk uploads succeed, further chunk uploads get a 503. -1 disables. */
    @Volatile var failChunksAfter = -1

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    private val skip = setOf("host", "content-length", "connection", "expect", "upgrade", "transfer-encoding", "keep-alive")

    init {
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/") { ex ->
            try {
                val body = ex.requestBody.readBytes()
                largestBody.accumulateAndGet(body.size.toLong(), ::maxOf)
                val isChunk = ex.requestHeaders.containsKey("X-Up2k-Hash")
                if (body.size > capBytes) {
                    respond(ex, 413, "request too large for the proxy")
                    return@createContext
                }
                if (isChunk) {
                    if (failChunksAfter >= 0 && chunkPosts.get() >= failChunksAfter) {
                        respond(ex, 503, "proxy says no")
                        return@createContext
                    }
                    chunkPosts.incrementAndGet()
                    if (ex.requestHeaders.containsKey("X-Up2k-Subc")) subchunkPosts.incrementAndGet()
                }
                val b = HttpRequest.newBuilder(URI(upstream + ex.requestURI.toString())).timeout(Duration.ofSeconds(60))
                ex.requestHeaders.forEach { (k, vs) -> if (k.lowercase() !in skip) vs.forEach { b.header(k, it) } }
                b.method(ex.requestMethod,
                    if (body.isEmpty() && ex.requestMethod == "GET") HttpRequest.BodyPublishers.noBody()
                    else HttpRequest.BodyPublishers.ofByteArray(body))
                val resp = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
                resp.headers().firstValue("content-type").ifPresent { ex.responseHeaders.add("Content-Type", it) }
                val out = resp.body()
                ex.sendResponseHeaders(resp.statusCode(), if (out.isEmpty()) -1 else out.size.toLong())
                if (out.isNotEmpty()) ex.responseBody.use { it.write(out) }
            } catch (e: Exception) {
                runCatching { respond(ex, 502, e.toString()) }
            } finally {
                ex.close()
            }
        }
        server.start()
    }

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, code: Int, text: String) {
        val b = text.toByteArray()
        ex.sendResponseHeaders(code, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    val baseUrl get() = "http://127.0.0.1:$port"

    fun reset() {
        chunkPosts.set(0)
        subchunkPosts.set(0)
        largestBody.set(0)
        failChunksAfter = -1
    }

    override fun close() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }
}
