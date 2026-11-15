package org.example.projectname

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.example.projectname.log.AppLog
import org.example.projectname.session.MainViewModel
import org.example.projectname.ui.AppTheme
import org.example.projectname.ui.MainScreen
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** The export in progress, so a second press while the file is being written does nothing. */
    private var export: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                MainScreen(
                    state = state,
                    config = viewModel.config,
                    previewHost = viewModel,
                    onRecordPressed = viewModel::onRecordPressed,
                    onExportLog = ::exportLog,
                    onBannerDismissed = viewModel::onBannerDismissed,
                    onNoticeShown = viewModel::onNoticeShown,
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // There is no foreground service yet, so a recording must not carry on out of sight.
        if (!isChangingConfigurations) viewModel.onAppHidden()
    }

    /**
     * Shares the redacted debug log through the share sheet as a .txt file. A file, rather than the text
     * itself, because Android limits what one share can pass between apps to about 1 MB.
     */
    private fun exportLog() {
        if (export?.isActive == true) return
        export = lifecycleScope.launch {
            val file = try {
                viewModel.writeDebugReport()
            } catch (e: IOException) {
                AppLog.e(TAG, "Couldn't write the log file to share", e)
                Toast.makeText(this@MainActivity, R.string.export_log_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            share(file)
        }
    }

    /** Offers [file] to the app the user picks. That app can read this one file, for this share only. */
    private fun share(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.logs", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_log_subject))
                putExtra(Intent.EXTRA_STREAM, uri)
                // The ClipData carries the read grant through the chooser, and lets it show the file.
                clipData = ClipData.newUri(contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.export_log_chooser_title)))
        } catch (e: ActivityNotFoundException) {
            AppLog.w(TAG, "No app can receive the log", e)
            Toast.makeText(this, R.string.export_log_no_app, Toast.LENGTH_LONG).show()
        } catch (e: RuntimeException) {
            // The system reports some failures this way, for example a share that is too large to pass on.
            AppLog.e(TAG, "The share sheet didn't open", e)
            Toast.makeText(this, R.string.export_log_failed, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
