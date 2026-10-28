package com.mzgszm.apkofframp.core

import com.mzgszm.apkofframp.core.copyparty.Up2k
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Expected values were produced by u2c.py 2.25 itself (up2k_chunksize and its hashing loop). */
class Up2kTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun chunkSizesMatchU2c() {
        val cases = mapOf(
            0L to 1048576L,
            1L to 1048576L,
            (1L shl 20) to 1048576L,
            (256L shl 20) to 1048576L,
            (256L shl 20) + 1 to 1572864L,
            (512L shl 20) + 7 to 3145728L,
            (1L shl 30) to 4194304L,
            (8L shl 30) + 1 to 33554432L,
            (40L shl 30) to 33554432L,
        )
        for ((size, expected) in cases) assertEquals("size $size", expected, Up2k.chunkSize(size))
    }

    @Test fun chunkHashesMatchU2c() {
        // bytes (i % 251) for i in 0 until 1 MiB + 5: two chunks.
        val f = tmp.newFile("pattern.bin")
        f.writeBytes(ByteArray((1 shl 20) + 5) { (it % 251).toByte() })
        val chunks = Up2k.hashFile(f)
        assertEquals(2, chunks.size)
        assertEquals("Z9rVae78mGo7JCT1UW1aAoS7U9e1LXX17YgaaDCpV2XM", chunks[0].hash)
        assertEquals(0L, chunks[0].offset)
        assertEquals(1048576L, chunks[0].length)
        assertEquals("c4z5AWk7qnnkS5FXag_3X0fYW4yF-kvbDOJUH49tuqS1", chunks[1].hash)
        assertEquals(1048576L, chunks[1].offset)
        assertEquals(5L, chunks[1].length)
    }

    @Test fun hashIs44UrlSafeChars() {
        val f = tmp.newFile("x.bin")
        f.writeBytes(ByteArray(1000) { 0x7f })
        val h = Up2k.hashFile(f).single().hash
        assertEquals(44, h.length)
        assertEquals(h, h.filter { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
