package com.mzgszm.apkofframp

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzgszm.apkofframp.notify.Channels
import com.mzgszm.apkofframp.notify.Notifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm = ctx.getSystemService(NotificationManager::class.java)!!
    private val notifier = Notifier(ctx)

    @Before fun grant() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        notifier.ensureChannels()
        nm.cancelAll()
    }

    @Test fun everyChannelHasTheRightGroupAndImportance() {
        val expected = mapOf(
            Channels.PROGRESS to (Channels.GROUP_UPLOADS to NotificationManager.IMPORTANCE_LOW),
            Channels.COMPLETED to (Channels.GROUP_UPLOADS to NotificationManager.IMPORTANCE_LOW),
            Channels.ATTENTION to (Channels.GROUP_UPLOADS to NotificationManager.IMPORTANCE_DEFAULT),
            Channels.INSTANT to (Channels.GROUP_MONITORING to NotificationManager.IMPORTANCE_LOW),
        )
        for ((id, gi) in expected) {
            val ch = nm.getNotificationChannel(id)
            assertNotNull(id, ch)
            assertEquals(id, gi.first, ch.group)
            assertEquals(id, gi.second, ch.importance)
        }
        val groups = nm.notificationChannelGroups.map { it.id }.toSet()
        assertTrue(groups.containsAll(setOf(Channels.GROUP_UPLOADS, Channels.GROUP_MONITORING)))
    }

    private fun channelsPosted(): Set<String> {
        Thread.sleep(500)
        return nm.activeNotifications.map { it.notification.channelId }.toSet()
    }

    @Test fun uploadPostsToTheRightChannels() {
        notifier.progress("com.x.y", "Foo", "1.2.3", 10, 100, null)
        assertTrue(Channels.PROGRESS in channelsPosted())

        notifier.cancelProgress("com.x.y")
        notifier.completed("com.x.y", "Foo", "1.2.3", "https://h/apks/com.x.y/com.x.y.apk")
        val after = channelsPosted()
        assertTrue(Channels.COMPLETED in after)
        assertTrue(Channels.PROGRESS !in after)

        notifier.attention("com.x.y", "Upload failed: Foo", "Server unreachable", retry = true)
        assertTrue(Channels.ATTENTION in channelsPosted())

        // isGroup is true for every member of a group, so pick the child by the summary flag instead.
        val completed = nm.activeNotifications.first {
            it.notification.channelId == Channels.COMPLETED &&
                (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) == 0
        }
        assertEquals("Copy link", completed.notification.actions.single().title.toString())
    }
}
