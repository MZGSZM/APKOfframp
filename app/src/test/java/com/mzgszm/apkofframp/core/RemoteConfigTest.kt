package com.mzgszm.apkofframp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteConfigTest {
    private fun cfg(url: String, http: Boolean = false, pw: String? = null) =
        RemoteConfig(url, pw, RemoteConfig.mib(32), http)

    @Test fun parsesBasePath() {
        val r = cfg("https://files.example.com/apks/")
        assertEquals("https://files.example.com", r.origin)
        assertEquals(listOf("apks"), r.baseSegments)
        assertEquals("https://files.example.com/apks/", r.baseUrl())
    }

    @Test fun baseWithoutTrailingSlashAndWithPort() {
        val r = cfg("https://files.example.com:8443/a/b")
        assertEquals("https://files.example.com:8443", r.origin)
        assertEquals(listOf("a", "b"), r.baseSegments)
    }

    @Test fun rootBase() {
        val r = cfg("https://files.example.com")
        assertEquals(emptyList<String>(), r.baseSegments)
        assertEquals("https://files.example.com/com.x.y/", r.url(r.folder("com.x.y", null), dir = true))
    }

    @Test(expected = InsecureUrlException::class)
    fun plainHttpRefusedByDefault() {
        cfg("http://files.example.com/apks/")
    }

    @Test fun plainHttpAllowedWhenSwitchedOn() {
        assertEquals("http://10.0.0.5:3923", cfg("http://10.0.0.5:3923/apks/", http = true).origin)
    }

    @Test(expected = IllegalArgumentException::class)
    fun garbageRefused() {
        cfg("not a url")
    }

    @Test(expected = IllegalArgumentException::class)
    fun otherSchemesRefused() {
        cfg("ftp://files.example.com/")
    }

    @Test fun defaultFolderIsPackageName() {
        val r = cfg("https://h/apks/")
        assertEquals(listOf("apks", "com.example.app"), r.folder("com.example.app", null))
        assertEquals("https://h/apks/com.example.app/com.example.app.apk",
            r.url(r.folder("com.example.app", null) + "com.example.app.apk"))
    }

    @Test fun folderOverrideIsRelativeToBase() {
        val r = cfg("https://h/apks/")
        assertEquals(listOf("apks", "paid", "maps"), r.folder("com.example.app", "/paid/maps/"))
        assertEquals(listOf("apks", "com.example.app"), r.folder("com.example.app", "  "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun folderOverrideCannotEscape() {
        cfg("https://h/apks/").folder("com.x.y", "../other")
    }

    @Test fun linksArePercentEncoded() {
        val r = cfg("https://h/my%20apks/")
        assertEquals(listOf("my apks"), r.baseSegments)
        assertEquals("https://h/my%20apks/com.x.y/older/com.x.y_1.0%20b_5.apk",
            r.url(r.folder("com.x.y", null) + "older" + "com.x.y_1.0 b_5.apk"))
    }

    @Test fun passwordNeverInLinks() {
        // Credentials typed into the URL are dropped, and the PW header password never appears.
        val r = cfg("https://zack:hunter2@h/apks/", pw = "s3cret-pw")
        val urls = listOf(r.origin, r.baseUrl(), r.url(r.folder("com.x.y", null), dir = true),
            r.url(r.folder("com.x.y", null) + "com.x.y.apk"))
        for (u in urls) {
            assertFalse(u, u.contains("hunter2"))
            assertFalse(u, u.contains("zack"))
            assertFalse(u, u.contains("s3cret"))
            assertFalse(u, u.contains("@"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun tinyRequestCapRefused() {
        RemoteConfig("https://h/", null, 1024, false)
    }
}
