package org.example.projectname.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.example.projectname.R
import org.example.projectname.capture.CaptureConfig
import org.example.projectname.session.RecordButton
import org.example.projectname.session.RecordStatus
import org.example.projectname.session.ScreenState
import org.example.projectname.session.formatElapsed

/** How see-through the bars over the preview are. */
private const val OVERLAY_ALPHA = 0.82f

/**
 * The main screen: the preview fills it edge to edge, with a status strip along the top, a banner under it
 * for errors only, and the controls along the bottom.
 */
@Composable
fun MainScreen(
    state: ScreenState,
    config: CaptureConfig,
    previewHost: PreviewHost,
    onRecordPressed: () -> Unit,
    onExportLog: () -> Unit,
    onBannerDismissed: () -> Unit,
    onNoticeShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
        onNoticeShown()
    }
    val now by rememberClock(ticking = state.recordingSinceMillis != null)
    val sides = WindowInsets.safeDrawing

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PreviewSurface(previewHost, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            Overlay {
                StatusStrip(
                    status = state.recordStatus(now),
                    config = config,
                    modifier = Modifier
                        .windowInsetsPadding(sides.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.banner?.let { message ->
                Banner(
                    message = message,
                    onDismiss = onBannerDismissed,
                    modifier = Modifier
                        .windowInsetsPadding(sides.only(WindowInsetsSides.Horizontal))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            SnackbarHost(snackbar, Modifier.windowInsetsPadding(sides.only(WindowInsetsSides.Horizontal)))
            Overlay {
                ControlRow(
                    button = state.recordButton,
                    onRecordPressed = onRecordPressed,
                    onExportLog = onExportLog,
                    modifier = Modifier
                        .windowInsetsPadding(sides.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusStrip(status: RecordStatus, config: CaptureConfig, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val style = MaterialTheme.typography.labelLarge
        Text(stringResource(R.string.source_test_pattern), style = style)
        Text(stringResource(R.string.status_resolution, config.width, config.height), style = style)
        Text(stringResource(R.string.status_fps, config.fps), style = style)
        RecordStatusText(status)
    }
}

/** A see-through bar over the preview, edge to edge, so the system bars' icons stay readable on it. */
@Composable
private fun Overlay(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = OVERLAY_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
        content = content,
    )
}

@Composable
private fun RecordStatusText(status: RecordStatus) {
    val style = MaterialTheme.typography.labelLarge
    when (status) {
        is RecordStatus.Recording -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
            Spacer(Modifier.size(6.dp))
            Text(
                stringResource(R.string.status_recording, formatElapsed(status.elapsedMillis)),
                style = style,
                color = MaterialTheme.colorScheme.error,
            )
        }
        is RecordStatus.Failed -> Text(
            stringResource(R.string.status_record_failed),
            style = style,
            color = MaterialTheme.colorScheme.error,
        )
        RecordStatus.Idle -> Text(stringResource(R.string.status_not_recording), style = style)
        RecordStatus.Starting -> Text(stringResource(R.string.status_starting), style = style)
        RecordStatus.Stopping -> Text(stringResource(R.string.status_stopping), style = style)
        RecordStatus.Saving -> Text(stringResource(R.string.status_saving), style = style)
    }
}

@Composable
private fun Banner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.banner_dismiss)) }
        }
    }
}

@Composable
private fun ControlRow(
    button: RecordButton,
    onRecordPressed: () -> Unit,
    onExportLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val stop = button.label == RecordButton.Label.STOP
        val stateText = stringResource(if (stop) R.string.record_state_on else R.string.record_state_off)
        Button(
            onClick = onRecordPressed,
            enabled = button.enabled,
            colors = if (stop) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier.semantics { stateDescription = stateText },
        ) {
            Text(stringResource(if (stop) R.string.button_stop else R.string.button_record))
        }
        OutlinedButton(onClick = onExportLog) {
            Text(stringResource(R.string.button_export_log))
        }
    }
}

/** The monotonic clock, updated a few times a second while [ticking], for the elapsed time. */
@Composable
private fun rememberClock(ticking: Boolean): State<Long> = produceState(SystemClock.elapsedRealtime(), ticking) {
    value = SystemClock.elapsedRealtime()
    while (ticking) {
        delay(250)
        value = SystemClock.elapsedRealtime()
    }
}
