package com.mzgszm.apkofframp.core

import java.net.URLEncoder

/** File naming, version rules, and path encoding. Pure Kotlin so it is unit tested on the JVM. */
object Naming {
    /** Versions kept value meaning "never prune". */
    const val KEEP_ALL = -1

    private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun isValidPackage(id: String): Boolean = id.length <= 255 && PACKAGE_RE.matches(id)

    /** Only a version code higher than the last uploaded one counts. Equal or lower never uploads. */
    fun shouldUpload(installedCode: Long, lastUploadedCode: Long?): Boolean =
        lastUploadedCode == null || installedCode > lastUploadedCode

    /** One WorkManager unique name per package and version, so a sweep and instant mode cannot double up. */
    fun uploadWorkName(pkg: String, versionCode: Long): String = "upload:$pkg:$versionCode"

    fun extension(isSplit: Boolean): String = if (isSplit) "apks" else "apk"

    fun stableName(pkg: String, ext: String): String = "$pkg.$ext"

    /** Both names a current release can have, since an app can switch between single and split. */
    fun stableCandidates(pkg: String): List<String> = listOf("$pkg.apk", "$pkg.apks")

    fun stagingName(versionCode: Long, stable: String): String = ".incoming-$versionCode-$stable.part"

    fun isStagingFor(name: String, pkg: String): Boolean =
        name.startsWith(".incoming-") && (name.endsWith("-$pkg.apk.part") || name.endsWith("-$pkg.apks.part"))

    /** `<package>_<versionName>_<versionCode>.<ext>`, with `~n` before the extension on a name clash. */
    fun archiveName(pkg: String, versionName: String?, versionCode: Long, ext: String, dup: Int = 0): String {
        val suffix = if (dup > 0) "~$dup" else ""
        return "${pkg}_${sanitizeVersion(versionName)}_$versionCode$suffix.$ext"
    }

    fun sanitizeVersion(v: String?): String {
        val cleaned = (v ?: "").map { c ->
            if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '.' || c == '+' || c == '-') c else '_'
        }.joinToString("").take(64).trim('_', '.')
        return cleaned.ifEmpty { "unknown" }
    }

    /** Reads the version code back out of an archived name, or null if the name is not ours. */
    fun versionCodeFromArchive(pkg: String, name: String): Long? {
        if (!name.startsWith("${pkg}_")) return null
        val ext = extensionOf(name)
        if (ext != "apk" && ext != "apks") return null
        val stem = name.substringBeforeLast('.')
        return stem.substringAfterLast('_').substringBefore('~').toLongOrNull()
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "")

    /** Percent-encodes one path segment. Spaces become %20, never '+'. */
    fun encodeSegment(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun encodePath(segments: List<String>, dir: Boolean): String {
        if (segments.isEmpty()) return "/"
        val body = segments.joinToString("/") { encodeSegment(it) }
        return "/$body" + if (dir) "/" else ""
    }

    /** Decoded absolute path, as copyparty's ?move= parameter expects before URL encoding. */
    fun vpath(segments: List<String>, dir: Boolean = false): String {
        if (segments.isEmpty()) return "/"
        return "/" + segments.joinToString("/") + if (dir) "/" else ""
    }
}
