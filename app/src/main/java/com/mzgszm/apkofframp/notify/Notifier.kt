package com.mzgszm.apkofframp.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mzgszm.apkofframp.MainActivity
import com.mzgszm.apkofframp.R
import java.util.UUID

/**
 * Channel IDs are permanent. If a default ever has to change, delete the old channel
 * and add a new ID rather than reusing one for a different purpose.
 */
object Channels {
    const val GROUP_UPLOADS = "uploads"
    const val GROUP_MONITORING = "monitoring"
    const val PROGRESS = "upload_progress"
    const val COMPLETED = "upload_completed"
    const val ATTENTION = "upload_attention"
    const val INSTANT = "instant_mode"

    data class Spec(val id: String, val group: String, val importance: Int, val nameRes: Int, val descRes: Int)

    val all = listOf(
        Spec(PROGRESS, GROUP_UPLOADS, NotificationManager.IMPORTANCE_LOW, R.string.ch_progress, R.string.ch_progress_desc),
        Spec(COMPLETED, GROUP_UPLOADS, NotificationManager.IMPORTANCE_LOW, R.string.ch_completed, R.string.ch_completed_desc),
        Spec(ATTENTION, GROUP_UPLOADS, NotificationManager.IMPORTANCE_DEFAULT, R.string.ch_attention, R.string.ch_attention_desc),
        Spec(INSTANT, GROUP_MONITORING, NotificationManager.IMPORTANCE_LOW, R.string.ch_instant, R.string.ch_instant_desc),
    )
}

class Notifier(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val sys = context.getSystemService(NotificationManager::class.java)!!
        sys.createNotificationChannelGroups(
            listOf(
                NotificationChannelGroup(Channels.GROUP_UPLOADS, context.getString(R.string.group_uploads)),
                NotificationChannelGroup(Channels.GROUP_MONITORING, context.getString(R.string.group_monitoring)),
            )
        )
        sys.createNotificationChannels(
            Channels.all.map { s ->
                NotificationChannel(s.id, context.getString(s.nameRes), s.importance).apply {
                    description = context.getString(s.descRes)
                    group = s.group
                    setShowBadge(s.id == Channels.ATTENTION)
                }
            }
        )
    }

    fun canPost(): Boolean =
        nm.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun post(id: Int, n: android.app.Notification) {
        // Without the permission notifications stop, but uploads keep going.
        if (!canPost()) return
        try {
            nm.notify(id, n)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    fun cancelProgress(pkg: String) = nm.cancel(progressId(pkg))
    fun cancelAttention(pkg: String) = nm.cancel(attentionId(pkg))

    private fun openDetail(pkg: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("offramp://app/$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, pkg.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun progress(pkg: String, label: String, version: String, sent: Long, total: Long, cancel: PendingIntent?) {
        val pct = if (total > 0) ((sent * 100) / total).toInt() else 0
        val b = NotificationCompat.Builder(context, Channels.PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_offramp)
            .setContentTitle("Uploading $label $version")
            .setContentText(if (total > 0) "${mib(sent)} of ${mib(total)}" else "Preparing")
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openDetail(pkg))
        cancel?.let { b.addAction(0, "Cancel", it) }
        post(progressId(pkg), b.build())
    }

    fun completed(pkg: String, label: String, version: String, link: String) {
        val copy = PendingIntent.getActivity(
            context, ("copy$pkg").hashCode(),
            CopyLinkActivity.intent(context, link),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Channels.COMPLETED)
            .setSmallIcon(R.drawable.ic_stat_offramp)
            .setContentTitle("Uploaded $label $version")
            .setContentText(link)
            .setAutoCancel(true)
            .setGroup(GROUP_COMPLETED)
            .setContentIntent(openDetail(pkg))
            .addAction(0, "Copy link", copy)
            .build()
        post(completedId(pkg), n)
        val summary = NotificationCompat.Builder(context, Channels.COMPLETED)
            .setSmallIcon(R.drawable.ic_stat_offramp)
            .setContentTitle("Uploads finished")
            .setGroup(GROUP_COMPLETED)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        post(SUMMARY_ID, summary)
    }

    fun attention(pkg: String, title: String, text: String, retry: Boolean) {
        val b = NotificationCompat.Builder(context, Channels.ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_offramp)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openDetail(pkg))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
        if (retry) {
            val pi = PendingIntent.getBroadcast(
                context, ("retry$pkg").hashCode(), RetryReceiver.intent(context, pkg),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "Retry", pi)
        }
        post(attentionId(pkg), b.build())
    }

    /** Certificate warnings get their own ID so a later failure does not replace them. */
    fun certChanged(pkg: String, label: String, version: String) {
        val n = NotificationCompat.Builder(context, Channels.ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_offramp)
            .setContentTitle("Signing certificate changed: $label")
            .setContentText("$version was uploaded, but it is signed with a different key than the last upload.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$version was uploaded, but it is signed with a different key than the last upload. " +
                    "It will not install over the previous version on the target device."
            ))
            .setContentIntent(openDetail(pkg))
            .build()
        post(("cert$pkg").hashCode(), n)
    }

    fun instantNotification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, Channels.INSTANT)
            .setSmallIcon(R.drawable.ic_stat_offramp)
            .setContentTitle("Instant mode is on")
            .setContentText("Watching for app updates")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val GROUP_COMPLETED = "com.mzgszm.apkofframp.COMPLETED"
        const val SUMMARY_ID = 1
        const val INSTANT_ID = 2
        fun progressId(pkg: String) = ("progress$pkg").hashCode()
        fun completedId(pkg: String) = ("done$pkg").hashCode()
        fun attentionId(pkg: String) = ("attn$pkg").hashCode()
        fun mib(b: Long) = "%.1f MiB".format(b / 1_048_576.0)
        fun cancelIntent(context: Context, id: UUID): PendingIntent =
            androidx.work.WorkManager.getInstance(context).createCancelPendingIntent(id)
    }
}
