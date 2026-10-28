package com.mzgszm.apkofframp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.mzgszm.apkofframp.R
import com.mzgszm.apkofframp.data.AppStatus
import com.mzgszm.apkofframp.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.graph.installed.icon(pkg)?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
        }
    }
    val bmp = icon
    if (bmp != null) {
        Image(bmp, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_apps), null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2))
        }
    }
}

data class ChipColors(val container: Color, val content: Color)

/** Status colors come from scheme roles only, so every theme works. */
@Composable
fun statusColors(status: AppStatus): ChipColors {
    val c = MaterialTheme.colorScheme
    return when (status) {
        AppStatus.UP_TO_DATE -> ChipColors(c.secondaryContainer, c.onSecondaryContainer)
        AppStatus.UPLOADING, AppStatus.PENDING -> ChipColors(c.primaryContainer, c.onPrimaryContainer)
        AppStatus.FAILED -> ChipColors(c.errorContainer, c.onErrorContainer)
        AppStatus.PAUSED -> ChipColors(c.tertiaryContainer, c.onTertiaryContainer)
        AppStatus.WAITING -> ChipColors(c.surfaceVariant, c.onSurfaceVariant)
    }
}

fun statusLabel(status: AppStatus, progress: Int): String = when (status) {
    AppStatus.WAITING -> "Waiting"
    AppStatus.UP_TO_DATE -> "Up to date"
    AppStatus.PENDING -> "Pending"
    AppStatus.UPLOADING -> if (progress >= 0) "Uploading $progress%" else "Uploading"
    AppStatus.FAILED -> "Failed"
    AppStatus.PAUSED -> "Paused"
}

@Composable
fun StatusChip(status: AppStatus, progress: Int) {
    val colors = statusColors(status)
    Surface(color = colors.container, contentColor = colors.content, shape = RoundedCornerShape(8.dp)) {
        Text(statusLabel(status, progress), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GiB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MiB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KiB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

fun versionText(name: String?, code: Long?): String = when {
    code == null -> "none"
    name.isNullOrBlank() -> "($code)"
    else -> "$name ($code)"
}
