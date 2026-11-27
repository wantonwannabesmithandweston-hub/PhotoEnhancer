package com.privatephoto.enhancer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Runs the existing enhancement pipeline independently of MainActivity.
 * The selected photo remains in app-private processing; no photo is uploaded.
 */
class PhotoEnhancementWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "private_photo_enhancement"

        const val KEY_IMAGE_URI = "image_uri"
        const val KEY_STRENGTH = "strength"
        const val KEY_MODE = "mode"
        const val KEY_INSTRUCTIONS = "instructions"

        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_ERROR = "error"
        const val KEY_STAGE = "stage"
        const val KEY_BACKEND = "backend"
        const val KEY_COMPLETED_TILES = "completed_tiles"
        const val KEY_TOTAL_TILES = "total_tiles"
        const val KEY_PERCENT = "percent"
        const val KEY_ETA_SECONDS = "eta_seconds"

        private const val MAX_INPUT_DIMENSION = 768
        private const val NOTIFICATION_ID = 2401
        private const val CHANNEL_ID = "photo_enhancement"
        private const val CHANNEL_NAME = "Photo enhancement"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val imageUriText = inputData.getString(KEY_IMAGE_URI)
        if (imageUriText.isNullOrBlank()) {
            return@withContext Result.failure(
                workDataOf(KEY_ERROR to "The selected photo could not be found.")
            )
        }

        val strength = inputData.getString(KEY_STRENGTH) ?: "Balanced"
        val mode = inputData.getString(KEY_MODE) ?: "Natural Restore"
        val instructions = inputData.getString(KEY_INSTRUCTIONS).orEmpty().take(2000)

        val outputDirectory = File(applicationContext.filesDir, "enhanced-output")
        val outputFile = File(outputDirectory, "enhanced_${id}.jpg")
        var source: Bitmap? = null
        var enhanced: Bitmap? = null

        try {
            setForeground(createForegroundInfo("Preparing photo..."))
            setProgress(
                workDataOf(
                    KEY_STAGE to "Preparing photo",
                    KEY_PERCENT to 0,
                    KEY_IMAGE_URI to imageUriText,
                    KEY_STRENGTH to strength,
                    KEY_MODE to mode,
                    KEY_INSTRUCTIONS to instructions
                )
            )

            val decodedSource = decodeSource(Uri.parse(imageUriText))
            source = decodedSource
            if (isStopped) throw CancellationException("Enhancement was cancelled.")

            setProgress(
                workDataOf(
                    KEY_STAGE to "Loading on-device AI model",
                    KEY_PERCENT to 0
                )
            )

            var firstTileCompletedAt: Long? = null
            var lastProgressUpdateAt = 0L

            var activeBackend = "Selecting accelerator"
            val processedBitmap = PhotoEnhancementEngine(applicationContext).enhance(
                source = decodedSource,
                strength = strength,
                mode = mode,
                instructions = instructions,
                backendStatus = { backend ->
                    activeBackend = backend
                    setProgressAsync(
                        workDataOf(
                            KEY_STAGE to "AI processing",
                            KEY_BACKEND to backend,
                            KEY_PERCENT to 0
                        )
                    )
                },
                progress = { completed, total ->
                if (isStopped) {
                    throw CancellationException("Enhancement was cancelled.")
                }

                val now = SystemClock.elapsedRealtime()
                if (completed == 1) firstTileCompletedAt = now

                val remainingTiles = (total - completed).coerceAtLeast(0)
                val firstTileTime = firstTileCompletedAt
                val etaSeconds = if (
                    completed > 1 && remainingTiles > 0 && firstTileTime != null
                ) {
                    (((now - firstTileTime).toDouble() / (completed - 1)) *
                        remainingTiles / 1000.0).roundToInt().coerceAtLeast(1).toLong()
                } else {
                    0L
                }

                val percent = if (total > 0) completed * 100 / total else 0

                // Persist progress often enough for the UI, without writing to
                // WorkManager's database for every very fast tile.
                if (now - lastProgressUpdateAt >= 400L || completed == total) {
                    setProgressAsync(
                        workDataOf(
                            KEY_STAGE to "AI processing",
                            KEY_COMPLETED_TILES to completed,
                            KEY_TOTAL_TILES to total,
                            KEY_PERCENT to percent,
                            KEY_ETA_SECONDS to etaSeconds,
                            KEY_BACKEND to activeBackend
                        )
                    )
                    lastProgressUpdateAt = now
                }
            }

            enhanced = processedBitmap
            if (isStopped) throw CancellationException("Enhancement was cancelled.")

            setProgress(
                workDataOf(
                    KEY_STAGE to "Saving enhanced photo",
                    KEY_PERCENT to 99
                )
            )

            if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                throw IllegalStateException("Could not create the private output folder.")
            }

            // Keep only the latest completed image in app-private storage.
            outputDirectory.listFiles()?.forEach { oldFile ->
                if (oldFile != outputFile) oldFile.delete()
            }

            val saved = outputFile.outputStream().buffered().use { output ->
                processedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            }
            if (!saved) throw IllegalStateException("Could not save the enhanced image.")

            setProgress(
                workDataOf(
                    KEY_STAGE to "Complete",
                    KEY_PERCENT to 100
                )
            )

            Result.success(
                workDataOf(
                    KEY_OUTPUT_PATH to outputFile.absolutePath,
                    KEY_IMAGE_URI to imageUriText,
                    KEY_STRENGTH to strength,
                    KEY_MODE to mode,
                    KEY_INSTRUCTIONS to instructions
                )
            )

        } catch (cancelled: CancellationException) {
            outputFile.delete()
            throw cancelled
        } catch (error: Exception) {
            outputFile.delete()
            Result.failure(
                workDataOf(
                    KEY_ERROR to (error.message ?: "An unknown enhancement error occurred.")
                )
            )
        } finally {
            source?.let { if (!it.isRecycled) it.recycle() }
            enhanced?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun decodeSource(uri: Uri): Bitmap {
        val resolver = applicationContext.contentResolver

        // Read EXIF orientation from a separate stream. BitmapFactory does not
        // apply JPEG orientation metadata to the decoded pixels.
        val orientation = try {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        // Read image dimensions without loading the compressed photo into a
        // large byte array. The stream is reopened for the actual decode.
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: throw IllegalStateException("Could not open the selected photo.")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("The selected photo is not a valid image.")
        }

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MAX_INPUT_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_INPUT_DIMENSION
        ) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: throw IllegalStateException("Android could not decode the selected photo.")

        return applyExifOrientation(decoded, orientation)
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                matrix.setScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_180 -> {
                matrix.setRotate(180f)
            }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setScale(1f, -1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> {
                matrix.setRotate(90f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> {
                matrix.setRotate(-90f)
            }
            else -> return bitmap
        }

        return try {
            val oriented = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )
            if (oriented !== bitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            oriented
        } catch (_: Exception) {
            // Preserve the decoded photo if an unusual bitmap cannot be
            // transformed; enhancement can still proceed without rotation.
            bitmap
        }
    }

    private fun createForegroundInfo(message: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Private Photo Enhancer")
            .setContentText(message)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_delete,
                "Cancel",
                cancelIntent
            )
            .build()

        val foregroundType = when {
            Build.VERSION.SDK_INT >= 35 ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 34 ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> 0
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, foregroundType)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
