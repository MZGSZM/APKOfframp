package com.mzgszm.apkofframp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rules from the spec that are easiest to hold by reading the source:
 * who writes to the clipboard, where fixed colors live, no logging of secrets, no backups.
 * Gradle runs unit tests from the module directory, so paths are relative to app/.
 */
class SourceRulesTest {
    private val src = File("src/main/java")
    private val sources: Map<String, String> by lazy {
        check(src.isDirectory) { "Run from the app module directory" }
        src.walkTopDown().filter { it.extension == "kt" }.associate { it.relativeTo(src).path.replace('\\', '/') to it.readText() }
    }

    private fun filesContaining(s: String) = sources.filterValues { it.contains(s) }.keys.sorted()

    @Test fun onlyClipWritesToTheClipboard() {
        assertEquals(listOf("com/mzgszm/apkofframp/notify/CopyLinkActivity.kt"), filesContaining("setPrimaryClip"))
    }

    @Test fun clipboardIsOnlyWrittenFromCopyActions() {
        // Clip.copy is called by the Copy link activity and by Copy buttons on the detail screen, nowhere else.
        val callers = filesContaining("Clip.copy(")
        assertEquals(
            listOf("com/mzgszm/apkofframp/notify/CopyLinkActivity.kt", "com/mzgszm/apkofframp/ui/DetailScreen.kt"),
            callers,
        )
        val detail = sources.getValue("com/mzgszm/apkofframp/ui/DetailScreen.kt")
        Regex("""Clip\.copy\(""").findAll(detail).forEach { m ->
            val before = detail.substring(maxOf(0, m.range.first - 120), m.range.first)
            assertTrue("Clip.copy outside an onClick: ...$before", before.contains("onClick"))
        }
    }

    @Test fun fixedColorsOnlyInTheTheme() {
        val offenders = sources.filter { (path, text) ->
            !path.endsWith("ui/theme/Theme.kt") &&
                (Regex("""Color\(0x""").containsMatchIn(text) ||
                    Regex("""Color\.(Red|Green|Blue|Black|White|Gray|Yellow|Cyan|Magenta)\b""").containsMatchIn(text))
        }.keys
        assertEquals(emptySet<String>(), offenders)
    }

    @Test fun nothingLogs() {
        // No android.util.Log, no println, no HTTP logging interceptor: the PW header can never reach a log.
        assertEquals(emptyList<String>(), filesContaining("android.util.Log"))
        assertEquals(emptyList<String>(), filesContaining("okhttp3.logging"))
        assertEquals(emptyList<String>(), filesContaining("println("))
        assertEquals(emptyList<String>(), filesContaining("printStackTrace"))
    }

    @Test fun passwordOnlyTravelsInThePwHeader() {
        val client = sources.getValue("com/mzgszm/apkofframp/core/copyparty/CopypartyClient.kt")
        assertTrue(client.contains("""header("PW", it)"""))
        // The password is never put into a URL builder.
        val remote = sources.getValue("com/mzgszm/apkofframp/core/RemoteConfig.kt")
        assertTrue(remote.contains(""".password("")"""))
    }

    @Test fun backupsAreOff() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(rules.contains("<cloud-backup>") && rules.contains("<device-transfer>"))
        assertTrue(!rules.contains("<include"))
    }

    @Test fun onlyTheLauncherIsExported() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertEquals(1, Regex("""android:exported="true"""").findAll(manifest).count())
        val launcher = manifest.substringBefore("""android:exported="true"""").substringAfterLast("<activity")
        assertTrue(launcher.contains(".MainActivity"))
    }

    @Test fun noGooglePlayServicesOrFirebase() {
        val build = File("build.gradle.kts").readText() + File("../gradle/libs.versions.toml").readText()
        assertTrue(!build.contains("com.google.android.gms"))
        assertTrue(!build.contains("firebase"))
    }
}
