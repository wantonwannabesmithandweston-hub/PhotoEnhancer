package com.privatephoto.enhancer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.android.gms.litert.LiteRt
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.io.File
import kotlin.math.exp
import kotlin.math.sqrt

object GfpganFaceRestorer {
    private const val MODEL_NAME = "gfpgan_fp16.tflite"
    private const val FACE_SIZE = 512
    private const val MAX_FACES = 5

    private data class Point(val x: Double, val y: Double)
    private data class Similarity(val a: Double, val b: Double, val tx: Double, val ty: Double) {
        fun matrix(): Matrix = Matrix().apply {
            setValues(floatArrayOf(a.toFloat(), (-b).toFloat(), tx.toFloat(), b.toFloat(), a.toFloat(), ty.toFloat(), 0f, 0f, 1f))
        }
    }

    fun restore(
        context: Context,
        enhanced: Bitmap,
        detectionImage: Bitmap,
        strength: String,
        onProgress: (Int, Int) -> Unit
    ): Bitmap {
        val modelFile = File(context.filesDir, MODEL_NAME)
        if (!modelFile.exists() || modelFile.length() < 400_000_000L) {
            throw IllegalStateException("Face restoration model is not fully downloaded.")
        }

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.04f)
            .build()
        val detector = FaceDetection.getClient(options)
        val faces = try {
            Tasks.await(detector.process(InputImage.fromBitmap(detectionImage, 0)))
        } finally {
            detector.close()
        }.take(MAX_FACES)

        if (faces.isEmpty()) {
            onProgress(0, 0)
            return enhanced.copy(Bitmap.Config.ARGB_8888, true)
        }

        var environment: Environment? = null
        var model: CompiledModel? = null
        var inputs: List<TensorBuffer> = emptyList()
        var outputs: List<TensorBuffer> = emptyList()
        val result = enhanced.copy(Bitmap.Config.ARGB_8888, true)
        try {
            environment = Tasks.await(LiteRt.initialize(context.applicationContext))
            model = CompiledModel.create(modelFile.absolutePath, CompiledModel.Options(Accelerator.GPU), environment)
            inputs = model.createInputBuffers()
            outputs = model.createOutputBuffers()
            if (inputs.size != 1 || outputs.size != 1) throw IllegalStateException("Unexpected GFPGAN tensor count")
            val input = inputs[0]
            val output = outputs[0]
            val scaleX = enhanced.width.toDouble() / detectionImage.width.toDouble()
            val scaleY = enhanced.height.toDouble() / detectionImage.height.toDouble()
            // A stronger but still identity-conscious blend makes the restoration visible.
            val blend = when (strength) { "Low" -> 0.48f; "High" -> 0.76f; "Ultra" -> 0.84f; else -> 0.65f }
            var restoredCount = 0

            faces.forEachIndexed { index, face ->
                val points = faceLandmarks(face, scaleX, scaleY)
                if (points == null) {
                    onProgress(restoredCount, faces.size)
                    return@forEachIndexed
                }
                val transform = fitSimilarity(points, templatePoints())
                if (transform == null) {
                    onProgress(restoredCount, faces.size)
                    return@forEachIndexed
                }
                val aligned = Bitmap.createBitmap(FACE_SIZE, FACE_SIZE, Bitmap.Config.ARGB_8888)
                Canvas(aligned).drawBitmap(result, transform.matrix(), Paint(Paint.FILTER_BITMAP_FLAG))
                val tensor = FloatArray(3 * FACE_SIZE * FACE_SIZE)
                val pixels = IntArray(FACE_SIZE * FACE_SIZE)
                aligned.getPixels(pixels, 0, FACE_SIZE, 0, 0, FACE_SIZE, FACE_SIZE)
                for (y in 0 until FACE_SIZE) {
                    for (x in 0 until FACE_SIZE) {
                        val p = pixels[y * FACE_SIZE + x]
                        val i = y * FACE_SIZE + x
                        tensor[i] = (Color.red(p) / 127.5f) - 1f
                        tensor[FACE_SIZE * FACE_SIZE + i] = (Color.green(p) / 127.5f) - 1f
                        tensor[2 * FACE_SIZE * FACE_SIZE + i] = (Color.blue(p) / 127.5f) - 1f
                    }
                }
                input.writeFloat(tensor)
                model.run(inputs, outputs)
                val restoredValues = output.readFloat()
                if (restoredValues.size != tensor.size || restoredValues.any { it.isNaN() || it.isInfinite() }) {
                    aligned.recycle()
                    throw IllegalStateException("GFPGAN returned invalid face data")
                }
                val restoredPixels = IntArray(FACE_SIZE * FACE_SIZE)
                for (y in 0 until FACE_SIZE) {
                    for (x in 0 until FACE_SIZE) {
                        val i = y * FACE_SIZE + x
                        val r = ((restoredValues[i] + 1f) * 127.5f).toInt().coerceIn(0, 255)
                        val g = ((restoredValues[FACE_SIZE * FACE_SIZE + i] + 1f) * 127.5f).toInt().coerceIn(0, 255)
                        val b = ((restoredValues[2 * FACE_SIZE * FACE_SIZE + i] + 1f) * 127.5f).toInt().coerceIn(0, 255)
                        val dx = (x - 256f) / 235f
                        val dy = (y - 275f) / 255f
                        val radius = sqrt(dx * dx + dy * dy)
                        val feather = ((1.04f - radius) / 0.18f).coerceIn(0f, 1f)
                        val smooth = feather * feather * (3f - 2f * feather)
                        val alpha = (255f * blend * smooth).toInt().coerceIn(0, 255)
                        restoredPixels[i] = Color.argb(alpha, r, g, b)
                    }
                }
                val restored = Bitmap.createBitmap(restoredPixels, FACE_SIZE, FACE_SIZE, Bitmap.Config.ARGB_8888)
                val inverse = Matrix()
                if (transform.matrix().invert(inverse)) {
                    Canvas(result).drawBitmap(restored, inverse, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
                aligned.recycle()
                restored.recycle()
                restoredCount++
                onProgress(restoredCount, faces.size)
            }
            return result
        } catch (e: Exception) {
            result.recycle()
            throw e
        } finally {
            inputs.forEach { try { it.close() } catch (_: Exception) {} }
            outputs.forEach { try { it.close() } catch (_: Exception) {} }
            try { model?.close() } catch (_: Exception) {}
            try { environment?.close() } catch (_: Exception) {}
        }
    }

    private fun faceLandmarks(face: Face, sx: Double, sy: Double): List<Point>? {
        fun p(type: Int): Point? = face.getLandmark(type)?.position?.let { Point(it.x * sx, it.y * sy) }
        val rightEye = p(FaceLandmark.RIGHT_EYE) ?: return null
        val leftEye = p(FaceLandmark.LEFT_EYE) ?: return null
        val nose = p(FaceLandmark.NOSE_BASE) ?: return null
        val mouthRight = p(FaceLandmark.MOUTH_RIGHT) ?: return null
        val mouthLeft = p(FaceLandmark.MOUTH_LEFT) ?: return null
        return listOf(rightEye, leftEye, nose, mouthRight, mouthLeft)
    }

    private fun templatePoints(): List<Point> = listOf(
        Point(192.98138, 239.94708),
        Point(318.90277, 240.19360),
        Point(256.63416, 314.01935),
        Point(201.26117, 371.41043),
        Point(313.08905, 371.15118)
    )

    private fun fitSimilarity(source: List<Point>, target: List<Point>): Similarity? {
        if (source.size != target.size || source.size < 3) return null
        val sx = source.map { it.x }.average()
        val sy = source.map { it.y }.average()
        val tx = target.map { it.x }.average()
        val ty = target.map { it.y }.average()
        var denominator = 0.0
        var aNumerator = 0.0
        var bNumerator = 0.0
        for (i in source.indices) {
            val x = source[i].x - sx
            val y = source[i].y - sy
            val u = target[i].x - tx
            val v = target[i].y - ty
            denominator += x * x + y * y
            aNumerator += x * u + y * v
            bNumerator += x * v - y * u
        }
        if (denominator < 1e-6) return null
        val a = aNumerator / denominator
        val b = bNumerator / denominator
        return Similarity(a, b, tx - a * sx + b * sy, ty - b * sx - a * sy)
    }
}
