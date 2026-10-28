package com.mzgszm.apkofframp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzgszm.apkofframp.notify.CopyLinkActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CopyLinkTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** Reads the clipboard from inside a focused activity, since background reads return nothing. */
    private fun readClip(scenario: ActivityScenario<MainActivity>): String? {
        var text: String? = null
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity { a ->
                if (a.hasWindowFocus()) {
                    text = a.getSystemService(ClipboardManager::class.java)!!.primaryClip?.getItemAt(0)?.text?.toString()
                }
            }
            if (text != null) return text
            Thread.sleep(200)
        }
        return text
    }

    @Test fun copyLinkActionPutsTheStableLinkOnTheClipboard() {
        val link = "https://files.example.com/apks/com.x.y/com.x.y.apk"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.getSystemService(ClipboardManager::class.java)!!.setPrimaryClip(ClipData.newPlainText("t", "before"))
            }
            Thread.sleep(500)
            // The same intent the Completed notification's action uses.
            ctx.startActivity(CopyLinkActivity.intent(ctx, link))
            Thread.sleep(1500)
            assertEquals(link, readClip(scenario))
        }
    }

    @Test fun openingTheAppDoesNotTouchTheClipboard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.getSystemService(ClipboardManager::class.java)!!.setPrimaryClip(ClipData.newPlainText("t", "untouched"))
            }
            Thread.sleep(2000)
            assertEquals("untouched", readClip(scenario))
        }
    }
}
