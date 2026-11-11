package com.wowcam.app.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A pending destination in the public Movies/WowCam folder.
 *
 * Android 10+ uses a MediaStore pending item and, where possible, muxes directly into the
 * MediaStore file descriptor. If a provider rejects the descriptor, the muxer transparently
 * falls back to an app-cache file which is copied into the pending item on [commit].
 *
 * Android 9 and below writes to the public Movies/WowCam directory (WRITE_EXTERNAL_STORAGE)
 * and asks MediaScanner to index the finished file.
 */
class VideoDestination private constructor(
    private val context: Context,
    private val resolver: ContentResolver,
    private val uri: Uri?,
    private val legacyFile: File?,
    private val tempDir: File,
    private val saf: Boolean = false
) {
    private var pendingFd: ParcelFileDescriptor? = null
    private var fallbackFile: File? = null
    private var committed = false
    private var aborted = false
    private var committedUri: Uri? = null

    /**
     * Creates a muxer targeting this destination.
     *
     * The caller must release the muxer before calling [commit] or [abort].
     */
    fun openMuxer(): MediaMuxer {
        check(!committed && !aborted) { "Destination already finalized" }
        check(pendingFd == null && fallbackFile == null) { "Muxer already created" }

        // SAF (SD card) destinations always mux into cache first; commit() then copies
        // into the document provider. This avoids provider-specific descriptor quirks.
        if (saf) {
            tempDir.mkdirs()
            val fallback = File(tempDir, "mux_${System.nanoTime()}.mp4")
            fallbackFile = fallback
            return MediaMuxer(
                fallback.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
            try {
                val descriptor = resolver.openFileDescriptor(uri, "rw")
                    ?: throw IOException("MediaStore returned no file descriptor")
                pendingFd = descriptor
                return MediaMuxer(
                    descriptor.fileDescriptor,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Direct MediaStore mux unavailable; using cache fallback", t)
                closePendingFd()
            }
        }

        val directFile = legacyFile
        if (directFile != null) {
            directFile.parentFile?.mkdirs()
            return MediaMuxer(
                directFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
        }

        // API 29+ URI fallback. The muxer writes to cache, then commit() copies into
        // the pending MediaStore item.
        tempDir.mkdirs()
        val fallback = File(tempDir, "mux_${System.nanoTime()}.mp4")
        fallbackFile = fallback
        return MediaMuxer(
            fallback.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
    }

    /**
     * Finalizes the pending MediaStore item (or indexes the legacy file).
     *
     * @return a content:// URI suitable for sharing, or null when indexing was unavailable.
     */
    fun commit(): Uri? {
        check(!aborted) { "Destination was aborted" }
        if (committed) return committedUri

        closePendingFd()

        val targetUri = uri
        if (targetUri != null) {
            val fallback = fallbackFile
            if (fallback != null && fallback.exists()) {
                copyIntoUri(targetUri, fallback)
                fallback.delete()
            }
            fallbackFile = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !saf) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(targetUri, values, null, null)
            }

            committed = true
            committedUri = targetUri
            return targetUri
        }

        val file = legacyFile ?: throw IOException("No output file")
        committed = true
        committedUri = scanLegacyFile(file)
        return committedUri
    }

    /**
     * Deletes the pending item/file. Safe to call multiple times.
     */
    fun abort() {
        if (committed || aborted) return
        aborted = true

        closePendingFd()
        fallbackFile?.delete()
        fallbackFile = null

        if (uri != null) {
            uri?.let { target -> runCatching { resolver.delete(target, null, null) } }
        }
        legacyFile?.delete()
    }

    private fun copyIntoUri(target: Uri, source: File) {
        val output = resolver.openOutputStream(target, "wt")
            ?: throw IOException("Could not open MediaStore output")
        output.use { out ->
            FileInputStream(source).use { input ->
                input.copyTo(out, COPY_BUFFER_BYTES)
            }
        }
    }

    private fun scanLegacyFile(file: File): Uri? {
        var scanned: Uri? = null
        val latch = CountDownLatch(1)

        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(MIME_TYPE)
        ) { _, uri ->
            scanned = uri
            latch.countDown()
        }

        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return scanned
    }

    private fun closePendingFd() {
        runCatching { pendingFd?.close() }
        pendingFd = null
    }

    companion object {
        private const val TAG = "WowCamDestination"
        private const val MIME_TYPE = "video/mp4"
        private const val FOLDER_NAME = "WowCam"
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /**
         * Creates a destination inside a user-chosen SAF folder. The caller grants a
         * persistable URI permission once; new documents are created with [displayName].
         */
        fun forSaf(
            context: Context,
            treeUri: Uri,
            displayName: String,
            tempDir: File
        ): VideoDestination {
            val appContext = context.applicationContext
            val resolver = appContext.contentResolver
            tempDir.mkdirs()

            val documentUri = try {
                DocumentsContract.createDocument(resolver, treeUri, MIME_TYPE, displayName)
                    ?: throw IOException("Could not create document in the chosen folder")
            } catch (t: Throwable) {
                throw IOException("SD card folder is not writable", t)
            }

            return VideoDestination(
                appContext,
                resolver,
                documentUri,
                null,
                tempDir,
                saf = true
            )
        }

        fun create(context: Context, displayName: String, tempDir: File): VideoDestination {
            val appContext = context.applicationContext
            val resolver = appContext.contentResolver
            tempDir.mkdirs()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/$FOLDER_NAME"
                    )
                    put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Video.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                )
                val uri = resolver.insert(collection, values)
                    ?: throw IOException("Could not create MediaStore entry")
                return VideoDestination(
                    appContext,
                    resolver,
                    uri,
                    null,
                    tempDir
                )
            }

            if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
                throw IOException("External storage is not mounted")
            }
            val movies = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES
            )
            val folder = File(movies, FOLDER_NAME)
            if (!folder.exists() && !folder.mkdirs()) {
                throw IOException("Could not create Movies/$FOLDER_NAME")
            }

            val file = File(folder, displayName)
            return VideoDestination(
                appContext,
                resolver,
                null,
                file,
                tempDir
            )
        }
    }
}
