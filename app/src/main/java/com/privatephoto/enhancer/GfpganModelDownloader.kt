package com.privatephoto.enhancer

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object GfpganModelDownloader {
    private const val MODEL_URL = "https://huggingface.co/litert-community/GFPGAN-v1.4-LiteRT/resolve/c741afa/gfpgan_fp16.tflite?download=true"
    private const val MIN_MODEL_BYTES = 400_000_000L

    fun download(context: Context, onProgress: (Long, Long) -> Unit): File {
        val destination = File(context.filesDir, "gfpgan_fp16.tflite")
        if (destination.exists() && destination.length() >= MIN_MODEL_BYTES) return destination

        if (context.filesDir.usableSpace < MIN_MODEL_BYTES + 50_000_000L) {
            throw IllegalStateException("Not enough free phone storage. Please free at least 450 MB and try again.")
        }

        val partial = File(context.filesDir, "gfpgan_fp16.tflite.part")
        if (partial.exists()) partial.delete()

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "PrivatePhotoEnhancer-Android")
        }

        try {
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Model server returned HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException("Download cancelled")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                    output.fd.sync()
                }
            }
            if (partial.length() < MIN_MODEL_BYTES) {
                throw IllegalStateException("Downloaded model is incomplete (${partial.length()} bytes)")
            }
            if (destination.exists()) destination.delete()
            if (!partial.renameTo(destination)) {
                throw IllegalStateException("Could not move the downloaded model into place")
            }
            return destination
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
