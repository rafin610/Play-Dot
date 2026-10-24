package com.streambox.mobile.ui.update

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streambox.mobile.data.update.UpdateSeverity
import kotlin.math.roundToInt

/**
 * Update prompt shown above the app content. Optional updates can be dismissed
 * with Later; mandatory updates have no dismiss path and ignore the persisted
 * skip value in [UpdateViewModel].
 */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit,
    onInstall: () -> Unit,
) {
    val prompt = state.prompt ?: return
    val mandatory = prompt.severity == UpdateSeverity.MANDATORY
    val busy = state.downloading || state.verifying

    AlertDialog(
        onDismissRequest = {
            if (!mandatory && !busy) onLater()
        },
        title = {
            Text(
                text = if (mandatory) "Update required" else "Update available",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = buildString {
                        append("Play Dot ")
                        append(prompt.metadata.latestVersionName)
                        append(" is available.")
                        if (state.currentVersionName.isNotBlank()) {
                            append(" You have ")
                            append(state.currentVersionName)
                            append('.')
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mandatory) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "This version is no longer supported. Update to keep watching.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val notes = prompt.metadata.releaseNotes.trim()
                if (notes.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "What's new",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (prompt.metadata.sizeBytes > 0L) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Download size: ${shortSize(prompt.metadata.sizeBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val progress = state.progress
                if (state.downloading) {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = progress?.let { "Downloading update… ${(it * 100).roundToInt()}%" }
                            ?: "Downloading update…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                if (state.verifying) {
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Verifying update…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                if (state.apkReady) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Download verified. Tap Install update to open the Android installer.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                val error = state.errorMessage
                if (error != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when {
                state.apkReady -> {
                    Button(onClick = onInstall) {
                        Text("Install update")
                    }
                }
                busy -> {
                    Button(onClick = {}, enabled = false) {
                        Text(if (state.verifying) "Verifying…" else "Downloading…")
                    }
                }
                state.errorMessage != null -> {
                    Button(onClick = onUpdateNow) {
                        Text("Retry")
                    }
                }
                else -> {
                    Button(onClick = onUpdateNow) {
                        Text("Update now")
                    }
                }
            }
        },
        dismissButton = {
            if (!mandatory) {
                TextButton(
                    onClick = onLater,
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text("Later")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun shortSize(bytes: Long): String =
    Formatter.formatShortFileSize(LocalContext.current, bytes)
