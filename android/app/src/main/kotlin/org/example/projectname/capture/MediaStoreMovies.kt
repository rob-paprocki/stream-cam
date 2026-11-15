package org.example.projectname.capture

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import org.example.projectname.core.recording.APP_FILE_PREFIX
import org.example.projectname.log.AppLog
import java.io.File
import java.io.IOException

/**
 * [SharedMovies] through MediaStore: the folder `Movies/PROJECT_NAME/`. From Android 10 an app may add to
 * the shared folders and see its own entries there with no storage permission.
 */
class MediaStoreMovies(context: Context) : SharedMovies {
    private val resolver: ContentResolver = context.contentResolver
    private val packageName: String = context.packageName
    private val collection: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** The folder inside shared Movies that recordings go to, as MediaStore writes it. */
    private val relativePath = "${Environment.DIRECTORY_MOVIES}/$APP_FILE_PREFIX/"

    override fun publish(file: File, displayName: String, takenAtMillis: Long): String {
        val values = ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, displayName)
            put(MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.VideoColumns.DATE_TAKEN, takenAtMillis)
            put(MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(collection, values) ?: throw IOException("The phone wouldn't make a new file in Movies.")
        try {
            val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("The new file in Movies couldn't be opened.")
            stream.use { output -> file.inputStream().use { input -> input.copyTo(output, COPY_BUFFER_BYTES) } }
            val done = ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, done, null, null) != 1) throw IOException("The new file in Movies couldn't be finished.")
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (cleanup: Exception) {
                AppLog.w(TAG, "Couldn't remove the half-copied file", cleanup)
            }
            throw if (e is IOException) e else IOException("Copying to Movies failed: ${e.message}", e)
        }
        // MediaStore adds a number to the name if the folder already has a file called that.
        val savedName = try {
            resolver.query(uri, arrayOf(MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: RuntimeException) {
            AppLog.w(TAG, "Couldn't read back the saved file's name", e)
            null
        }
        return relativePath + (savedName ?: displayName)
    }

    override fun removePending(): Int {
        val ids = ArrayList<Long>()
        queryPending()?.use { cursor -> while (cursor.moveToNext()) ids += cursor.getLong(0) }
        var removed = 0
        for (id in ids) {
            if (resolver.delete(includingPending(ContentUris.withAppendedId(collection, id)), null, null) > 0) removed++
        }
        return removed
    }

    override fun hasFinished(stem: String, sizeBytes: Long): Boolean {
        // A plain query leaves pending entries out. The name is compared here rather than with LIKE, whose
        // _ would match any character.
        val selection = "${MediaColumns.RELATIVE_PATH} = ? AND ${MediaColumns.SIZE} = ?"
        val args = arrayOf(relativePath, sizeBytes.toString())
        resolver.query(collection, arrayOf(MediaColumns.DISPLAY_NAME), selection, args, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(0)?.startsWith(stem) == true) return true
            }
        }
        return false
    }

    /** This app's pending entries in its folder. A plain query leaves pending entries out, so ask for them. */
    private fun queryPending(): Cursor? {
        val selection =
            "${MediaColumns.RELATIVE_PATH} = ? AND ${MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaColumns.IS_PENDING} = 1"
        val args = arrayOf(relativePath, packageName)
        val projection = arrayOf(BaseColumns._ID)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val query = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(collection, projection, query, null)
        } else {
            resolver.query(includingPending(collection), projection, selection, args, null)
        }
    }

    /** On Android 10, a URI only reaches pending entries when it says so. Later versions ignore this. */
    private fun includingPending(uri: Uri): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            uri
        } else {
            @Suppress("DEPRECATION")
            MediaStore.setIncludePending(uri)
        }

    private companion object {
        const val TAG = "MediaStoreMovies"
        const val COPY_BUFFER_BYTES = 1 shl 20
    }
}
