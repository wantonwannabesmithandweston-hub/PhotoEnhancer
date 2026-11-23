package com.privatephoto.enhancer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment as LiteRtEnvironment
import com.google.ai.edge.litert.TensorBuffer
import com.google.android.gms.litert.LiteRt
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The existing on-device enhancement pipeline, separated from the Activity
 * so WorkManager can run it without depending on the screen lifecycle.
 */
class PhotoEnhancementEngine(private val appContext: Context) {

    companion object {
        private const val PHOTO_MODEL_BYTES = 33_724_311L
        private const val TILE_SIZE = 128
        private const val OUTPUT_TILE_SIZE = 512
        private const val CHANNELS = 3
        private const val TILE_STRIDE = 96
        private const val INPUT_ELEMENTS = TILE_SIZE * TILE_SIZE * CHANNELS
        private const val OUTPUT_ELEMENTS = OUTPUT_TILE_SIZE * OUTPUT_TILE_SIZE * CHANNELS
    }

    private val modelFile = "Real-ESRGAN-General-x4v3.tflite"
    private val photoQualityModelFile by lazy {
        File(appContext.filesDir, "realesrgan_x4plus_w16.onnx")
    }

    private fun hasPhotoQualityModel(): Boolean =
        photoQualityModelFile.isFile &&
            photoQualityModelFile.length() == PHOTO_MODEL_BYTES

    fun enhance(
        source: Bitmap,
        strength: String,
        mode: String,
        instructions: String,
        progress: (Int, Int) -> Unit
    ): Bitmap {
        val aiBitmap = runActiveRealEsrgan(source, strength, progress)
        return try {
            applyLocalEditing(
                aiBitmap,
                interpretInstructions(strength, mode, instructions)
            )
        } finally {
            if (!aiBitmap.isRecycled) aiBitmap.recycle()
        }
    }


    private data class EditSettings(
        var brightness: Float = 0f,
        var contrast: Float = 1f,
        var saturation: Float = 1f,
        var warmth: Float = 0f,
        var sharpen: Float = 0.4f,
        var noiseReduction: Float = 0.12f,
        var grayscale: Boolean = false
    )

    private fun interpretInstructions(
        strength: String,
        mode: String,
        instructions: String
    ): EditSettings {

        val settings = EditSettings()

        // Gentle brightness correction to prevent dark results.
        settings.brightness = 0.05f

        when (strength) {
            "Low" -> {
                settings.contrast = 1.02f
                settings.saturation = 1.01f
                settings.sharpen = 0.20f
            }
            "Balanced" -> {
                settings.contrast = 1.04f
                settings.saturation = 1.03f
                settings.sharpen = 0.40f
            }
            "High" -> {
                settings.contrast = 1.07f
                settings.saturation = 1.05f
                settings.sharpen = 0.65f
            }
            "Ultra" -> {
                settings.contrast = 1.10f
                settings.saturation = 1.07f
                settings.sharpen = 0.85f
            }
        }

        when (mode) {
            "Natural Restore" -> {
                settings.noiseReduction = 0.30f
                settings.sharpen *= 0.60f
                settings.contrast *= 0.97f
                settings.saturation *= 0.98f
            }
            "Balanced" -> {
                settings.noiseReduction = 0.16f
            }
            "Maximum Detail" -> {
                settings.noiseReduction = 0.04f
                settings.sharpen *= 1.25f
                settings.contrast *= 1.02f
            }
        }

        val text = instructions.lowercase()

        if (
            text.contains("brighter") ||
            text.contains("brighten") ||
            text.contains("more light") ||
            text.contains("increase brightness")
        ) settings.brightness += 0.12f

        if (
            text.contains("darker") ||
            text.contains("darken") ||
            text.contains("less light")
        ) settings.brightness -= 0.12f

        if (
            text.contains("more contrast") ||
            text.contains("increase contrast")
        ) settings.contrast += 0.12f

        if (
            text.contains("less contrast") ||
            text.contains("softer contrast")
        ) settings.contrast -= 0.10f

        if (
            text.contains("vivid") ||
            text.contains("more colorful") ||
            text.contains("more colour") ||
            text.contains("more color") ||
            text.contains("increase saturation")
        ) settings.saturation += 0.18f

        if (
            text.contains("less saturation") ||
            text.contains("muted colors") ||
            text.contains("muted colours")
        ) settings.saturation -= 0.20f

        if (
            text.contains("warmer") ||
            text.contains("warm tones") ||
            text.contains("warm lighting")
        ) settings.warmth += 0.10f

        if (
            text.contains("cooler") ||
            text.contains("cool tones") ||
            text.contains("cool lighting")
        ) settings.warmth -= 0.10f

        if (
            text.contains("sharper") ||
            text.contains("sharpen") ||
            text.contains("more detail") ||
            text.contains("crisper")
        ) settings.sharpen += 0.30f

        if (
            text.contains("softer") ||
            text.contains("less sharp") ||
            text.contains("reduce sharpness")
        ) settings.sharpen -= 0.25f

        if (
            text.contains("no sharpening") ||
            text.contains("disable sharpening")
        ) settings.sharpen = 0f

        if (
            text.contains("black and white") ||
            text.contains("black & white") ||
            text.contains("grayscale") ||
            text.contains("greyscale")
        ) settings.grayscale = true

        if (
            text.contains("less noise") ||
            text.contains("reduce noise") ||
            text.contains("remove noise") ||
            text.contains("denoise")
        ) {
            settings.noiseReduction =
                (settings.noiseReduction + 0.20f).coerceAtMost(0.65f)
        }

        settings.brightness =
            settings.brightness.coerceIn(-0.30f, 0.30f)
        settings.contrast =
            settings.contrast.coerceIn(0.75f, 1.55f)
        settings.saturation =
            settings.saturation.coerceIn(0f, 1.60f)
        settings.warmth =
            settings.warmth.coerceIn(-0.25f, 0.25f)
        settings.sharpen =
            settings.sharpen.coerceIn(0f, 1.5f)
        settings.noiseReduction =
            settings.noiseReduction.coerceIn(0f, 0.70f)

        return settings
    }

    private fun applyLocalEditing(
        source: Bitmap,
        settings: EditSettings
    ): Bitmap {

        val width = source.width
        val height = source.height

        val output = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        val previous = IntArray(width)
        val current = IntArray(width)
        val next = IntArray(width)
        val outputRow = IntArray(width)

        for (y in 0 until height) {
            val previousY = (y - 1).coerceAtLeast(0)
            val nextY = (y + 1).coerceAtMost(height - 1)

            source.getPixels(previous, 0, width, 0, previousY, width, 1)
            source.getPixels(current, 0, width, 0, y, width, 1)
            source.getPixels(next, 0, width, 0, nextY, width, 1)

            for (x in 0 until width) {
                val center = current[x]
                val left = current[(x - 1).coerceAtLeast(0)]
                val right = current[(x + 1).coerceAtMost(width - 1)]
                val top = previous[x]
                val bottom = next[x]

                val r = Color.red(center).toFloat()
                val g = Color.green(center).toFloat()
                val b = Color.blue(center).toFloat()

                val nr = (
                    Color.red(left) + Color.red(right) +
                    Color.red(top) + Color.red(bottom)
                ) / 4f

                val ng = (
                    Color.green(left) + Color.green(right) +
                    Color.green(top) + Color.green(bottom)
                ) / 4f

                val nb = (
                    Color.blue(left) + Color.blue(right) +
                    Color.blue(top) + Color.blue(bottom)
                ) / 4f

                val difference = (
                    abs(r - nr) + abs(g - ng) + abs(b - nb)
                ) / 3f

                val edgeProtection =
                    (1f - difference / 48f).coerceIn(0f, 1f)

                val denoise = settings.noiseReduction * edgeProtection

                val cleanR = r + (nr - r) * denoise
                val cleanG = g + (ng - g) * denoise
                val cleanB = b + (nb - b) * denoise

                val edgeStrength =
                    (difference / 40f).coerceIn(0.15f, 1f)

                var outR = cleanR +
                    settings.sharpen * edgeStrength * (cleanR - nr)

                var outG = cleanG +
                    settings.sharpen * edgeStrength * (cleanG - ng)

                var outB = cleanB +
                    settings.sharpen * edgeStrength * (cleanB - nb)

                outR = (outR - 127.5f) * settings.contrast +
                    127.5f + settings.brightness * 255f

                outG = (outG - 127.5f) * settings.contrast +
                    127.5f + settings.brightness * 255f

                outB = (outB - 127.5f) * settings.contrast +
                    127.5f + settings.brightness * 255f

                outR += settings.warmth * 100f
                outB -= settings.warmth * 100f

                val luminance =
                    0.299f * outR +
                    0.587f * outG +
                    0.114f * outB

                val highlightProtection =
                    1f - ((luminance - 210f) / 45f).coerceIn(0f, 1f)

                val gray = luminance

                val saturation = 1f +
                    (settings.saturation - 1f) * highlightProtection

                outR = gray + (outR - gray) * saturation
                outG = gray + (outG - gray) * saturation
                outB = gray + (outB - gray) * saturation

                if (settings.grayscale) {
                    outR = gray
                    outG = gray
                    outB = gray
                }

                outputRow[x] = Color.rgb(
                    outR.roundToInt().coerceIn(0, 255),
                    outG.roundToInt().coerceIn(0, 255),
                    outB.roundToInt().coerceIn(0, 255)
                )
            }

            output.setPixels(outputRow, 0, width, 0, y, width, 1)
        }

        return output
    }

    private fun createTileStarts(length: Int): List<Int> {
        if (length <= TILE_SIZE) return listOf(0)

        val starts = mutableListOf(0)

        while (true) {
            val next = starts.last() + TILE_STRIDE

            if (next + TILE_SIZE >= length) {
                val finalStart = (length - TILE_SIZE).coerceAtLeast(0)

                if (finalStart > starts.last()) {
                    starts.add(finalStart)
                }
                break
            }

            starts.add(next)
        }

        return starts
    }

    private fun runActiveRealEsrgan(
        source: Bitmap,
        strength: String,
        progress: (Int, Int) -> Unit
    ): Bitmap {
        return if (hasPhotoQualityModel()) {
            runRealEsrganOnnx(source, strength, progress)
        } else {
            runRealEsrgan(source, strength, progress)
        }
    }

    private fun runRealEsrganOnnx(
        source: Bitmap,
        strength: String,
        progress: (Int, Int) -> Unit
    ): Bitmap {
        val environment = OrtEnvironment.getEnvironment()
        environment.setTelemetry(false)
        var options: OrtSession.SessionOptions? = null
        var session: OrtSession? = null
        var destination: Bitmap? = null

        try {
            val xnnpackThreads = Runtime.getRuntime()
                .availableProcessors()
                .coerceAtMost(4)
                .coerceAtLeast(1)

            // Prefer NNAPI on Android 8.1+ while retaining XNNPACK for
            // operators the device accelerator cannot execute. If provider
            // registration or session creation fails, retry with XNNPACK,
            // then the default CPU execution provider.
            val providerAttempts = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    add("NNAPI + XNNPACK")
                }
                add("XNNPACK")
                add("CPU")
            }

            var selectedSession: OrtSession? = null
            var lastProviderError: Exception? = null

            for (provider in providerAttempts) {
                val candidateOptions = OrtSession.SessionOptions()
                try {
                    candidateOptions.setIntraOpNumThreads(1)

                    when (provider) {
                        "NNAPI + XNNPACK" -> {
                            candidateOptions.addNnapi()
                            candidateOptions.addXnnpack(
                                mapOf("intra_op_num_threads" to xnnpackThreads.toString())
                            )
                        }
                        "XNNPACK" -> {
                            candidateOptions.addXnnpack(
                                mapOf("intra_op_num_threads" to xnnpackThreads.toString())
                            )
                        }
                        // CPU is ONNX Runtime's default provider.
                    }

                    selectedSession = environment.createSession(
                        photoQualityModelFile.absolutePath,
                        candidateOptions
                    )
                    options = candidateOptions
                    break
                } catch (providerError: Exception) {
                    try { candidateOptions.close() } catch (_: Exception) {}
                    lastProviderError = providerError
                }
            }

            val activeSession = selectedSession ?: throw Exception(
                "Could not start the high-detail AI model: ${lastProviderError?.message}",
                lastProviderError
            )
            session = activeSession
            val inputName = activeSession.inputNames.firstOrNull()
                ?: throw Exception("High-detail model has no input")
            val outputName = activeSession.outputNames.firstOrNull()
                ?: throw Exception("High-detail model has no output")

            val width = source.width
            val height = source.height
            val outputBitmap = Bitmap.createBitmap(
                width * 4,
                height * 4,
                Bitmap.Config.ARGB_8888
            )
            destination = outputBitmap

            val sourcePixels = IntArray(width * height)
            source.getPixels(sourcePixels, 0, width, 0, 0, width, height)

            val planeSize = TILE_SIZE * TILE_SIZE
            val inputData = FloatArray(INPUT_ELEMENTS)
            val xStarts = createTileStarts(width)
            val yStarts = createTileStarts(height)
            val totalTiles = xStarts.size * yStarts.size
            var tileNumber = 0

            val saturation = when (strength) {
                "Low" -> 1.00f
                "High" -> 1.02f
                "Ultra" -> 1.03f
                else -> 1.01f
            }

            for (yIndex in yStarts.indices) {
                val tileY = yStarts[yIndex]

                for (xIndex in xStarts.indices) {
                    val tileX = xStarts[xIndex]

                    for (y in 0 until TILE_SIZE) {
                        for (x in 0 until TILE_SIZE) {
                            val sourceX = (tileX + x).coerceAtMost(width - 1)
                            val sourceY = (tileY + y).coerceAtMost(height - 1)
                            val pixel = sourcePixels[sourceY * width + sourceX]

                            val r0 = Color.red(pixel) / 255f
                            val g0 = Color.green(pixel) / 255f
                            val b0 = Color.blue(pixel) / 255f
                            val gray = 0.299f * r0 + 0.587f * g0 + 0.114f * b0

                            val index = y * TILE_SIZE + x
                            inputData[index] = (gray + (r0 - gray) * saturation)
                                .coerceIn(0f, 1f)
                            inputData[planeSize + index] =
                                (gray + (g0 - gray) * saturation).coerceIn(0f, 1f)
                            inputData[2 * planeSize + index] =
                                (gray + (b0 - gray) * saturation).coerceIn(0f, 1f)
                        }
                    }

                    val inputTensor = OnnxTensor.createTensor(
                        environment,
                        FloatBuffer.wrap(inputData),
                        longArrayOf(1, 3, TILE_SIZE.toLong(), TILE_SIZE.toLong())
                    )

                    val aiOutput = try {
                        activeSession.run(mapOf(inputName to inputTensor)).use { results ->
                            val output = results.get(outputName).orElse(null) as? OnnxTensor
                                ?: throw Exception("High-detail model output is not a tensor")
                            val buffer = output.floatBuffer
                            if (buffer.remaining() != OUTPUT_ELEMENTS) {
                                throw Exception("Unexpected high-detail model output size")
                            }
                            FloatArray(OUTPUT_ELEMENTS).also { buffer.get(it) }
                        }
                    } finally {
                        inputTensor.close()
                    }

                    if (aiOutput.any { it.isNaN() || it.isInfinite() }) {
                        throw Exception("High-detail model returned invalid values")
                    }

                    val tilePixels = IntArray(OUTPUT_TILE_SIZE * OUTPUT_TILE_SIZE)
                    val outputPlane = OUTPUT_TILE_SIZE * OUTPUT_TILE_SIZE

                    for (y in 0 until OUTPUT_TILE_SIZE) {
                        for (x in 0 until OUTPUT_TILE_SIZE) {
                            val index = y * OUTPUT_TILE_SIZE + x
                            val r = (aiOutput[index] * 255f)
                                .roundToInt().coerceIn(0, 255)
                            val g = (aiOutput[outputPlane + index] * 255f)
                                .roundToInt().coerceIn(0, 255)
                            val b = (aiOutput[2 * outputPlane + index] * 255f)
                                .roundToInt().coerceIn(0, 255)
                            tilePixels[index] = Color.rgb(r, g, b)
                        }
                    }

                    val cropLeft = if (xIndex == 0) 0 else (
                        (xStarts[xIndex - 1] + TILE_SIZE - tileX) / 2
                    ).coerceIn(0, TILE_SIZE)

                    val cropTop = if (yIndex == 0) 0 else (
                        (yStarts[yIndex - 1] + TILE_SIZE - tileY) / 2
                    ).coerceIn(0, TILE_SIZE)

                    val cropRight = if (xIndex == xStarts.lastIndex) {
                        minOf(TILE_SIZE, width - tileX)
                    } else {
                        ((tileX + TILE_SIZE + xStarts[xIndex + 1]) / 2 - tileX)
                            .coerceIn(cropLeft, TILE_SIZE)
                    }

                    val cropBottom = if (yIndex == yStarts.lastIndex) {
                        minOf(TILE_SIZE, height - tileY)
                    } else {
                        ((tileY + TILE_SIZE + yStarts[yIndex + 1]) / 2 - tileY)
                            .coerceIn(cropTop, TILE_SIZE)
                    }

                    val copyWidth = (cropRight - cropLeft) * 4
                    val copyHeight = (cropBottom - cropTop) * 4

                    if (copyWidth > 0 && copyHeight > 0) {
                        val outputOffset =
                            cropTop * 4 * OUTPUT_TILE_SIZE + cropLeft * 4
                        outputBitmap.setPixels(
                            tilePixels,
                            outputOffset,
                            OUTPUT_TILE_SIZE,
                            (tileX + cropLeft) * 4,
                            (tileY + cropTop) * 4,
                            copyWidth,
                            copyHeight
                        )
                    }

                    tileNumber++
                    progress(tileNumber, totalTiles)
                }
            }

            return outputBitmap

        } catch (e: Exception) {
            destination?.let { if (!it.isRecycled) it.recycle() }
            throw e
        } finally {
            try { session?.close() } catch (_: Exception) {}
            try { options?.close() } catch (_: Exception) {}
        }
    }

    private fun runRealEsrgan(
        source: Bitmap,
        strength: String,
        progress: (Int, Int) -> Unit
    ): Bitmap {

        var environment: LiteRtEnvironment? = null
        var model: CompiledModel? = null

        var inputBuffers: List<TensorBuffer> = emptyList()
        var outputBuffers: List<TensorBuffer> = emptyList()

        var resultBitmap: Bitmap? = null

        try {
            environment = Tasks.await(
                LiteRt.initialize(appContext)
            )

            model = CompiledModel.create(
                appContext.assets,
                modelFile,
                CompiledModel.Options(Accelerator.CPU),
                environment
            )

            inputBuffers = model.createInputBuffers()
            outputBuffers = model.createOutputBuffers()

            if (inputBuffers.size != 1 || outputBuffers.size != 1) {
                throw Exception("Unexpected AI model tensor count")
            }

            val inputBuffer = inputBuffers[0]
            val outputBuffer = outputBuffers[0]

            val width = source.width
            val height = source.height

            val destination = Bitmap.createBitmap(
                width * 4,
                height * 4,
                Bitmap.Config.ARGB_8888
            )

            resultBitmap = destination

            val sourcePixels = IntArray(width * height)

            source.getPixels(
                sourcePixels, 0, width, 0, 0, width, height
            )

            val inputData = FloatArray(INPUT_ELEMENTS)

            val xStarts = createTileStarts(width)
            val yStarts = createTileStarts(height)

            val totalTiles = xStarts.size * yStarts.size
            var tileNumber = 0

            // Do not increase contrast before AI inference.
            // This helps preserve natural brightness and skin tones.
            val contrast = 1.00f

            val saturation = when (strength) {
                "Low" -> 1.00f
                "High" -> 1.02f
                "Ultra" -> 1.03f
                else -> 1.01f
            }

            for (yIndex in yStarts.indices) {
                val tileY = yStarts[yIndex]

                for (xIndex in xStarts.indices) {
                    val tileX = xStarts[xIndex]

                    for (y in 0 until TILE_SIZE) {
                        for (x in 0 until TILE_SIZE) {
                            val sourceX =
                                (tileX + x).coerceAtMost(width - 1)

                            val sourceY =
                                (tileY + y).coerceAtMost(height - 1)

                            val pixel =
                                sourcePixels[sourceY * width + sourceX]

                            var r = Color.red(pixel) / 255f
                            var g = Color.green(pixel) / 255f
                            var b = Color.blue(pixel) / 255f

                            r = ((r - 0.5f) * contrast + 0.5f)
                                .coerceIn(0f, 1f)

                            g = ((g - 0.5f) * contrast + 0.5f)
                                .coerceIn(0f, 1f)

                            b = ((b - 0.5f) * contrast + 0.5f)
                                .coerceIn(0f, 1f)

                            val gray =
                                0.299f * r + 0.587f * g + 0.114f * b

                            r = (gray + (r - gray) * saturation)
                                .coerceIn(0f, 1f)

                            g = (gray + (g - gray) * saturation)
                                .coerceIn(0f, 1f)

                            b = (gray + (b - gray) * saturation)
                                .coerceIn(0f, 1f)

                            val index =
                                (y * TILE_SIZE + x) * CHANNELS

                            inputData[index] = r
                            inputData[index + 1] = g
                            inputData[index + 2] = b
                        }
                    }

                    inputBuffer.writeFloat(inputData)
                    model.run(inputBuffers, outputBuffers)

                    val aiOutput = outputBuffer.readFloat()

                    if (aiOutput.size != OUTPUT_ELEMENTS) {
                        throw Exception("Unexpected AI output size")
                    }

                    val tilePixels = IntArray(
                        OUTPUT_TILE_SIZE * OUTPUT_TILE_SIZE
                    )

                    for (y in 0 until OUTPUT_TILE_SIZE) {
                        for (x in 0 until OUTPUT_TILE_SIZE) {
                            val index =
                                (y * OUTPUT_TILE_SIZE + x) * CHANNELS

                            val r = (aiOutput[index] * 255f)
                                .roundToInt().coerceIn(0, 255)

                            val g = (aiOutput[index + 1] * 255f)
                                .roundToInt().coerceIn(0, 255)

                            val b = (aiOutput[index + 2] * 255f)
                                .roundToInt().coerceIn(0, 255)

                            tilePixels[y * OUTPUT_TILE_SIZE + x] =
                                Color.rgb(r, g, b)
                        }
                    }

                    val cropLeft = if (xIndex == 0) {
                        0
                    } else {
                        (
                            xStarts[xIndex - 1] +
                            TILE_SIZE - tileX
                        ) / 2
                    }.coerceIn(0, TILE_SIZE)

                    val cropTop = if (yIndex == 0) {
                        0
                    } else {
                        (
                            yStarts[yIndex - 1] +
                            TILE_SIZE - tileY
                        ) / 2
                    }.coerceIn(0, TILE_SIZE)

                    val cropRight = if (xIndex == xStarts.lastIndex) {
                        minOf(TILE_SIZE, width - tileX)
                    } else {
                        (
                            tileX + TILE_SIZE +
                            xStarts[xIndex + 1]
                        ) / 2 - tileX
                    }.coerceIn(cropLeft, TILE_SIZE)

                    val cropBottom = if (yIndex == yStarts.lastIndex) {
                        minOf(TILE_SIZE, height - tileY)
                    } else {
                        (
                            tileY + TILE_SIZE +
                            yStarts[yIndex + 1]
                        ) / 2 - tileY
                    }.coerceIn(cropTop, TILE_SIZE)

                    val copyWidth = (cropRight - cropLeft) * 4
                    val copyHeight = (cropBottom - cropTop) * 4

                    if (copyWidth > 0 && copyHeight > 0) {
                        val outputOffset =
                            cropTop * 4 * OUTPUT_TILE_SIZE +
                            cropLeft * 4

                        destination.setPixels(
                            tilePixels,
                            outputOffset,
                            OUTPUT_TILE_SIZE,
                            (tileX + cropLeft) * 4,
                            (tileY + cropTop) * 4,
                            copyWidth,
                            copyHeight
                        )
                    }

                    tileNumber++
                    progress(tileNumber, totalTiles)
                }
            }

            return destination

        } catch (e: Exception) {
            resultBitmap?.let {
                if (!it.isRecycled) it.recycle()
            }
            throw e

        } finally {
            inputBuffers.forEach {
                try { it.close() } catch (_: Exception) {}
            }
            outputBuffers.forEach {
                try { it.close() } catch (_: Exception) {}
            }
            try { model?.close() } catch (_: Exception) {}
            try { environment?.close() } catch (_: Exception) {}
        }
    }

}
