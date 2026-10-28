package com.mzgszm.apkofframp.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class InsecureUrlException : IllegalArgumentException(
    "This URL uses plain HTTP. Turn on \"Allow unencrypted HTTP\" for this destination if you really want that."
)

/**
 * A parsed copyparty destination. The password is kept here for the PW header only and
 * never becomes part of any URL this class builds.
 */
class RemoteConfig(
    baseUrl: String,
    val password: String?,
    val maxRequestBytes: Long,
    allowHttp: Boolean,
) {
    /** scheme://host[:port], with any user info from the typed URL dropped. */
    val origin: String

    /** Decoded path segments of the base URL. */
    val baseSegments: List<String>

    init {
        val url = baseUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Not a valid http(s) URL")
        if (!url.isHttps && !allowHttp) throw InsecureUrlException()
        origin = url.newBuilder()
            .username("")
            .password("")
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
        baseSegments = url.pathSegments.filter { it.isNotEmpty() }
        require(maxRequestBytes >= MIN_REQUEST_BYTES) { "Maximum request size must be at least 64 KiB" }
    }

    /** Folder for an app: `<base>/<package>/`, or `<base>/<override>/` when a path override is set. */
    fun folder(pkg: String, override: String?): List<String> {
        val extra = override
            ?.split('/')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(pkg)
        require(extra.none { it == "." || it == ".." }) { "Folder override cannot contain . or .." }
        return baseSegments + extra
    }

    fun url(segments: List<String>, dir: Boolean = false): String = origin + Naming.encodePath(segments, dir)

    fun baseUrl(): String = url(baseSegments, dir = true)

    companion object {
        const val MIN_REQUEST_BYTES = 64L * 1024
        fun mib(n: Int): Long = n.toLong() * 1024 * 1024
    }
}
