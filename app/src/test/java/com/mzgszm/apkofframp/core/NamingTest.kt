package com.mzgszm.apkofframp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingTest {

    @Test fun higherVersionUploads() {
        assertTrue(Naming.shouldUpload(1231, 1230))
    }

    @Test fun equalVersionNeverUploads() {
        assertFalse(Naming.shouldUpload(1230, 1230))
    }

    @Test fun lowerVersionNeverUploads() {
        assertFalse(Naming.shouldUpload(1229, 1230))
        assertFalse(Naming.shouldUpload(1, Long.MAX_VALUE))
    }

    @Test fun firstVersionUploads() {
        assertTrue(Naming.shouldUpload(1, null))
    }

    @Test fun packageIdValidation() {
        listOf("com.example.app", "a.b", "com.Example_1.app2", "org.mozilla.firefox").forEach {
            assertTrue(it, Naming.isValidPackage(it))
        }
        listOf("", "example", "com.", ".com", "com..app", "1com.app", "com.1app", "com.ex-ample", "com.example app",
            "com/example", "com.example.", "a".repeat(250) + ".bcdefg").forEach {
            assertFalse(it, Naming.isValidPackage(it))
        }
    }

    @Test fun uploadJobNameIsUniquePerPackageAndVersion() {
        assertEquals(Naming.uploadWorkName("com.a.b", 5), Naming.uploadWorkName("com.a.b", 5))
        assertNotEquals(Naming.uploadWorkName("com.a.b", 5), Naming.uploadWorkName("com.a.b", 6))
        assertNotEquals(Naming.uploadWorkName("com.a.b", 5), Naming.uploadWorkName("com.a.c", 5))
    }

    @Test fun stableAndStagingNames() {
        assertEquals("com.example.app.apk", Naming.stableName("com.example.app", Naming.extension(false)))
        assertEquals("com.example.app.apks", Naming.stableName("com.example.app", Naming.extension(true)))
        assertEquals(".incoming-1230-com.example.app.apk.part", Naming.stagingName(1230, "com.example.app.apk"))
        assertTrue(Naming.isStagingFor(".incoming-1230-com.example.app.apk.part", "com.example.app"))
        assertTrue(Naming.isStagingFor(".incoming-7-com.example.app.apks.part", "com.example.app"))
        assertFalse(Naming.isStagingFor(".incoming-7-com.example.app2.apk.part", "com.example.app"))
        assertFalse(Naming.isStagingFor("com.example.app.apk", "com.example.app"))
    }

    @Test fun archiveNames() {
        assertEquals("com.example.app_2.5.0_1230.apk", Naming.archiveName("com.example.app", "2.5.0", 1230, "apk"))
        assertEquals("com.example.splitapp_8.0.1_7702.apks", Naming.archiveName("com.example.splitapp", "8.0.1", 7702, "apks"))
        assertEquals("com.x.y_unknown_5.apk", Naming.archiveName("com.x.y", null, 5, "apk"))
        assertEquals("com.x.y_1.0_beta_2_5.apk", Naming.archiveName("com.x.y", "1.0 beta/2", 5, "apk"))
        assertEquals("com.x.y_1.0_5~2.apk", Naming.archiveName("com.x.y", "1.0", 5, "apk", dup = 2))
    }

    @Test fun versionCodeReadBackFromArchiveName() {
        assertEquals(1230L, Naming.versionCodeFromArchive("com.example.app", "com.example.app_2.5.0_1230.apk"))
        assertEquals(7702L, Naming.versionCodeFromArchive("com.e.s", "com.e.s_8.0.1_7702.apks"))
        assertEquals(5L, Naming.versionCodeFromArchive("com.x.y", "com.x.y_1.0_5~2.apk"))
        assertEquals(5L, Naming.versionCodeFromArchive("com.x.y", "com.x.y_1_0_beta_5.apk"))
        assertNull(Naming.versionCodeFromArchive("com.x.y", "com.x.yz_1.0_5.apk"))
        assertNull(Naming.versionCodeFromArchive("com.x.y", "com.x.y_1.0_5.txt"))
        assertNull(Naming.versionCodeFromArchive("com.x.y", "notes.apk"))
    }

    @Test fun pathEncoding() {
        assertEquals("a%20b", Naming.encodeSegment("a b"))
        assertEquals("a%2Bb", Naming.encodeSegment("a+b"))
        assertEquals("%C3%A9", Naming.encodeSegment("é"))
        assertEquals("/apks/com.x/older/", Naming.encodePath(listOf("apks", "com.x", "older"), dir = true))
        assertEquals("/apks/com.x/a%20b.apk", Naming.encodePath(listOf("apks", "com.x", "a b.apk"), dir = false))
        assertEquals("/apks/com.x/a b.apk", Naming.vpath(listOf("apks", "com.x", "a b.apk")))
    }
}
