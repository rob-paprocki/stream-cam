package org.example.projectname.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Receives the preview surface's life cycle. The calls arrive on the main thread. */
interface PreviewHost {
    fun onPreviewSurfaceReady(surface: Surface, width: Int, height: Int)
    fun onPreviewSurfaceResized(width: Int, height: Int)

    /** The surface is about to be destroyed. Drawing to it must stop before this returns. */
    fun onPreviewSurfaceGone()
}

/**
 * The live preview: a SurfaceView that RootEncoder draws into. It keeps the screen awake for as long as it
 * is on screen (spec UI-9).
 */
@Composable
fun PreviewSurface(host: PreviewHost, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                keepScreenOn = true
                holder.addCallback(SurfaceCallbacks(host))
            }
        },
    )
}

private class SurfaceCallbacks(private val host: PreviewHost) : SurfaceHolder.Callback {
    private var attached = false

    override fun surfaceCreated(holder: SurfaceHolder) {
        // The size arrives in surfaceChanged, which always follows.
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (attached) {
            host.onPreviewSurfaceResized(width, height)
        } else {
            attached = true
            host.onPreviewSurfaceReady(holder.surface, width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        attached = false
        host.onPreviewSurfaceGone()
    }
}
