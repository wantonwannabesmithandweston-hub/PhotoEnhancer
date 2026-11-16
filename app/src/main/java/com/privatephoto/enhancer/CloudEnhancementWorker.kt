package com.privatephoto.enhancer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.Data
import java.io.File
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
        private const val CHANNEL_ID = "photo_enhancement"
        private const val NOTIFICATION_ID = 4107
        private const val SERVER = "https://photoenhancer.onrender.com"
        private const val MAX_UPLOAD = 20 * 1024 * 1024
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val source = inputData.getString(KEY_INPUT_PATH)?.let(::File)
        val sourceUri = inputData.getString(KEY_INPUT_URI)?.let(Uri::parse)
        if (source == null && sourceUri == null) {
            return@withContext Result.failure(errorData("Missing selected photo. Please choose the photo again."))
        }

        try {
            setForeground(createForegroundInfo("Preparing photo..."))
            val bytes = if (sourceUri != null) {
                applicationContext.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Android could not reopen the selected photo.")
            } else {
                source!!.readBytes()
            }
            if (bytes.isEmpty() || bytes.size > MAX_UPLOAD) {
                throw IllegalStateException("Photo is empty or exceeds 20 MB.")
            }
            val boundary = "----PrivatePhotoEnhancer${id}"
            val strength = inputData.getString(KEY_STRENGTH) ?: "Balanced"
            val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "image/jpeg"
            val fileName = if (mimeType == "image/png") "photo.png" else "photo.jpg"
            setProgress(Data.Builder().putLong(KEY_STARTED_AT, System.currentTimeMillis()).build())
            val connection = (URL("$SERVER/enhance").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 45_000
                readTimeout = 600_000
                doOutput = true
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
                    // Retry only responses that are plausibly temporary. HTTP 500 often
                    // means a server configuration problem (for example a missing API key);
                    // retrying it several times only leaves the user waiting for minutes.
                    if (code == 408 || code == 429 || code == 502 || code == 503 || code == 504) {
                        return@withContext if (runAttemptCount < 4) Result.retry()
                        else Result.failure(errorData("Cloud service is temporarily unavailable after several attempts. ${detail ?: "Please try again later."}".take(500)))
                    }
                    val message = "Cloud service error $code: ${detail ?: "Please try again."}"
                    return@withContext Result.failure(errorData(message.take(500)))
                }
                responseBytes = connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }

            if (responseBytes.isEmpty()) throw IllegalStateException("Cloud returned an empty image.")
            setForeground(createForegroundInfo("Saving enhanced photo..."))
            val savedUri = saveImage(responseBytes)
            source?.delete()
            Result.success(Data.Builder().putString(KEY_OUTPUT_URI, savedUri.toString()).build())
        } catch (e: Exception) {
            if (runAttemptCount < 4 && (e is java.io.IOException || e is java.net.SocketTimeoutException)) {
                Result.retry()
            } else {
                source?.delete()
                Result.failure(errorData("Cloud enhancement failed: ${e.message ?: "Please try again."}"))
            }
        }
    }

    private fun saveImage(bytes: ByteArray): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Enhanced_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Private Photo Enhancer")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = applicationContext.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: throw IllegalStateException("Could not create gallery image")
        try {
            applicationContext.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not save gallery image")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                applicationContext.contentResolver.update(uri, ready, null, null)
            }
            return uri
        } catch (e: Exception) {
            applicationContext.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun errorData(message: String) =
        Data.Builder().putString(KEY_ERROR, message).build()

    private fun createForegroundInfo(message: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Photo enhancement", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(applicationContext, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(applicationContext)
        }.setContentTitle("Private Photo Enhancer")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
