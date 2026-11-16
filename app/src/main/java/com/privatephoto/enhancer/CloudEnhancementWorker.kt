package com.privatephoto.enhancer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CloudEnhancementWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "private_photo_cloud_enhancement"
        const val KEY_INPUT_PATH = "input_path"
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_STRENGTH = "strength"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_ERROR = "error"
        const val KEY_STARTED_AT = "started_at_ms"
        private const val SERVER = "https://photoenhancer.onrender.com"
        private const val MAX_UPLOAD = 20 * 1024 * 1024
        private const val MAX_OUTPUT = 60 * 1024 * 1024
        private const val NOTIFICATION_CHANNEL = "photo_enhancement"
        private const val NOTIFICATION_ID = 4107
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Private Photo Enhancer")
            .setContentText("Restoring your photo. You can safely leave the app.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Photo enhancement",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while a photo is being enhanced"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Promote the job to a foreground service immediately. This is essential
        // for long network/image jobs when the Activity is no longer visible.
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            return@withContext Result.failure(errorData(
                "Android could not start the background enhancement service: ${e.message ?: "foreground service unavailable"}"
            ))
        }

        val source = inputData.getString(KEY_INPUT_PATH)?.let(::File)
        val sourceUri = inputData.getString(KEY_INPUT_URI)?.let(Uri::parse)
        if (source == null && sourceUri == null) {
            return@withContext Result.failure(errorData("Missing selected photo. Please choose the photo again."))
        }

        try {
            setProgress(Data.Builder()
                .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                .putString("stage", "preparing")
                .build())

            val bytes = if (sourceUri != null) {
                applicationContext.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Android could not reopen the selected photo. Please select it again.")
            } else {
                source!!.readBytes()
            }

            if (bytes.isEmpty() || bytes.size > MAX_UPLOAD) {
                throw IllegalStateException("Photo is empty or exceeds the 20 MB upload limit.")
            }

            val boundary = "----PrivatePhotoEnhancer${id}"
            val strength = inputData.getString(KEY_STRENGTH) ?: "Balanced"
            val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "image/jpeg"
            val fileName = if (mimeType.equals("image/png", true)) "photo.png" else "photo.jpg"

            setProgress(Data.Builder()
                .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                .putString("stage", "uploading")
                .build())

            val connection = (URL("$SERVER/enhance").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 480_000
                doOutput = true
                useCaches = false
                setRequestProperty("Accept", "image/png")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            val responseBytes: ByteArray
            try {
                connection.outputStream.use { output ->
                    fun writeText(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
                    writeText("--$boundary\r\n")
                    writeText("Content-Disposition: form-data; name=\"strength\"\r\n\r\n")
                    writeText("$strength\r\n")
                    writeText("--$boundary\r\n")
                    writeText("Content-Disposition: form-data; name=\"image\"; filename=\"$fileName\"\r\n")
                    writeText("Content-Type: $mimeType\r\n\r\n")
                    output.write(bytes)
                    writeText("\r\n--$boundary--\r\n")
                    output.flush()
                }

                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = try {
                        connection.errorStream?.bufferedReader()?.use { it.readText() }
                    } catch (_: Exception) { null }

                    val detailText = detail ?: "Please try again."
                    val isConfigurationError =
                        detailText.contains("not configured", ignoreCase = true) ||
                        detailText.contains("missing", ignoreCase = true) &&
                        detailText.contains("token", ignoreCase = true)

                    if ((code == 408 || code == 429 || code == 502 || code == 503 || code == 504) &&
                        !isConfigurationError && runAttemptCount < 3
                    ) {
                        return@withContext Result.retry()
                    }

                    return@withContext Result.failure(errorData(
                        "Cloud service returned HTTP $code. $detailText".take(700)
                    ))
                }

                val contentType = connection.contentType ?: ""
                if (!contentType.contains("image/", ignoreCase = true)) {
                    val detail = try {
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } catch (_: Exception) { "The server did not return an image." }
                    return@withContext Result.failure(errorData(
                        "Cloud response was not an image: $detail".take(700)
                    ))
                }

                responseBytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_OUTPUT) {
                            throw IllegalStateException("The enhanced image exceeded the 60 MB safety limit.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }

            if (responseBytes.size < 8 ||
                responseBytes[0] != 0x89.toByte() ||
                responseBytes[1] != 0x50.toByte() ||
                responseBytes[2] != 0x4E.toByte() ||
                responseBytes[3] != 0x47.toByte()
            ) {
                throw IllegalStateException("The cloud service response was not a valid PNG image.")
            }

            // Confirm Android can decode the returned image before publishing it.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(responseBytes, 0, responseBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IllegalStateException("The cloud service returned a damaged image.")
            }

            setProgress(Data.Builder()
                .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                .putString("stage", "saving")
                .build())

            val savedUri = saveImage(responseBytes)
            source?.delete()
            Result.success(Data.Builder().putString(KEY_OUTPUT_URI, savedUri.toString()).build())

        } catch (e: Exception) {
            val retryable = e is IOException
            if (retryable && runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure(errorData(
                    "Cloud enhancement failed: ${e.message ?: "Please try again."}".take(700)
                ))
            }
        }
    }

    private fun saveImage(bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Enhanced_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/Private Photo Enhancer"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = applicationContext.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IllegalStateException("Could not create the Gallery image.")

        try {
            applicationContext.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not write the enhanced image to Gallery.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                val updated = applicationContext.contentResolver.update(uri, ready, null, null)
                if (updated <= 0) {
                    throw IllegalStateException("The image was written but could not be published to Gallery.")
                }
            }
            return uri
        } catch (e: Exception) {
            applicationContext.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun errorData(message: String): Data =
        Data.Builder().putString(KEY_ERROR, message).build()
}
