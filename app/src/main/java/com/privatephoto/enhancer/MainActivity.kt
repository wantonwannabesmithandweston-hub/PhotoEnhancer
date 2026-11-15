
package com.privatephoto.enhancer

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment as LiteRtEnvironment
import com.google.ai.edge.litert.TensorBuffer
import com.google.android.gms.litert.LiteRt
import com.google.android.gms.tasks.Tasks

import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var photoView: ZoomableImageView
    private lateinit var compareButton: Button
    private lateinit var strengthText: TextView
    private lateinit var statusText: TextView
    private lateinit var enhanceButton: Button
    private lateinit var saveButton: Button
    private lateinit var uploadButton: Button
    private lateinit var testAiButton: Button
    private lateinit var instructionsInput: EditText

    private var selectedStrength = "Balanced"
    private var selectedImage: Uri? = null
    private var enhancedBitmap: Bitmap? = null
    private var isShowingEnhanced = false

    private val strengthButtons = mutableMapOf<String, Button>()
    private val executor = Executors.newSingleThreadExecutor()

    private val PICK_IMAGE = 100
    private val SAVE_DOCUMENT = 101

    private var isProcessing = false
    private var isSaving = false
    private var isModelTesting = false

    private val modelFile = "Real-ESRGAN-General-x4v3.tflite"

    companion object {
        private const val TILE_SIZE = 128
        private const val OUTPUT_TILE_SIZE = 512
        private const val CHANNELS = 3
        private const val TILE_STRIDE = 96

        private const val INPUT_ELEMENTS =
            TILE_SIZE * TILE_SIZE * CHANNELS

        private const val OUTPUT_ELEMENTS =
            OUTPUT_TILE_SIZE * OUTPUT_TILE_SIZE * CHANNELS

        private const val MAX_INPUT_DIMENSION = 768
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(20), dp(12), dp(20))
            setBackgroundColor(Color.rgb(20, 20, 20))
        }

        val title = TextView(this).apply {
            text = "Private Photo Enhancer"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        layout.addView(title)

        val subtitle = TextView(this).apply {
            text = "Free Offline AI Photo Editing"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        layout.addView(subtitle)

        photoView = ZoomableImageView(this).apply {
            setBackgroundColor(Color.rgb(35, 35, 35))
            scaleType = ImageView.ScaleType.MATRIX
        }

        layout.addView(
            photoView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(420)
            ).apply {
                setMargins(0, dp(20), 0, dp(8))
            }
        )

        compareButton = Button(this).apply {
            text = "View Original Photo"
            isEnabled = false
        }
        layout.addView(compareButton)

        compareButton.setOnClickListener {
            if (!isProcessing && !isSaving && enhancedBitmap != null) {
                isShowingEnhanced = !isShowingEnhanced
                displayCurrentPhoto()
            }
        }

        uploadButton = Button(this).apply {
            text = "Upload Picture"
        }
        layout.addView(uploadButton)

        uploadButton.setOnClickListener {
            if (!isProcessing && !isSaving && !isModelTesting) {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(intent, PICK_IMAGE)
            }
        }

        val instructionTitle = TextView(this).apply {
            text = "Custom Editing Instructions"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(8))
        }
        layout.addView(instructionTitle)

        instructionsInput = EditText(this).apply {
            this.hint = "Example: Make the photo brighter, increase sharpness, add warmth, and make colors more vivid..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            maxLines = 6
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(48, 48, 48))
        }

        layout.addView(
            instructionsInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(12))
            }
        )

        val instructionHelp = TextView(this).apply {
            text = "Supported: brighter, darker, sharper, softer, more contrast, vivid colors, less saturation, warmer, cooler, black and white."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        layout.addView(instructionHelp)

        val label = TextView(this).apply {
            text = "Enhancement Strength"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
        }
        layout.addView(label)

        strengthText = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.GREEN)
            gravity = Gravity.CENTER
        }
        layout.addView(strengthText)

        listOf("Low", "Balanced", "High", "Ultra").forEach { strength ->
            val button = Button(this).apply {
                text = strength
            }

            strengthButtons[strength] = button
            layout.addView(button)

            button.setOnClickListener {
                if (!isProcessing && !isSaving && !isModelTesting) {
                    selectedStrength = strength
                    updateStrengthButtons()
                }
            }
        }

        enhanceButton = Button(this).apply {
            text = "Enhance Photo"
        }
        layout.addView(enhanceButton)

        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        layout.addView(statusText)

        testAiButton = Button(this).apply {
            text = "Test AI Model"
        }
        layout.addView(testAiButton)

        testAiButton.setOnClickListener {
            testAiModel()
        }

        saveButton = Button(this).apply {
            text = "Save Enhanced Photo to Gallery"
            isEnabled = false
        }
        layout.addView(saveButton)

        enhanceButton.setOnClickListener {
            val uri = selectedImage

            if (uri == null) {
                Toast.makeText(
                    this,
                    "Please upload a photo first",
                    Toast.LENGTH_SHORT
                ).show()
            } else if (!isProcessing && !isSaving && !isModelTesting) {
                enhancePhoto(uri)
            }
        }

        saveButton.setOnClickListener {
            if (!isProcessing && !isSaving && !isModelTesting) {
                saveEnhancedPhoto()
            }
        }

        scroll.addView(layout)
        setContentView(scroll)

        updateStrengthButtons()
        statusText.text = "Upload a photo to get started"
    }

    private fun setControlsEnabled(enabled: Boolean) {
        enhanceButton.isEnabled = enabled
        uploadButton.isEnabled = enabled
        testAiButton.isEnabled = enabled
        instructionsInput.isEnabled = enabled

        saveButton.isEnabled =
            enabled &&
            enhancedBitmap != null &&
            !enhancedBitmap!!.isRecycled

        compareButton.isEnabled =
            enabled && enhancedBitmap != null

        strengthButtons.values.forEach {
            it.isEnabled = enabled
        }
    }

    private fun displayCurrentPhoto() {
        if (isShowingEnhanced) {
            val bitmap = enhancedBitmap

            if (bitmap != null && !bitmap.isRecycled) {
                photoView.setImageBitmap(bitmap)
                compareButton.text = "View Original Photo"
            }
        } else {
            val uri = selectedImage

            if (uri != null) {
                photoView.setImageURI(uri)
                compareButton.text = "View Enhanced Photo"
            }
        }

        photoView.resetZoom()
    }

    private fun updateStrengthButtons() {
        strengthText.text = "Selected: $selectedStrength"

        strengthButtons.forEach { (strength, button) ->
            val selected = strength == selectedStrength

            button.text =
                if (selected) "✓ $strength" else strength

            button.setTextColor(
                if (selected) Color.GREEN else Color.WHITE
            )
        }
    }

    private fun readPhotoBytes(uri: Uri): ByteArray {
        val stream = contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot open selected photo")

        return stream.use {
            it.readBytes()
        }
    }

    private fun testAiModel() {
        if (isModelTesting || isProcessing || isSaving) return

        isModelTesting = true
        setControlsEnabled(false)

        statusText.text = "Testing Real-ESRGAN AI model..."

        executor.execute {
            var environment: LiteRtEnvironment? = null
            var model: CompiledModel? = null

            var inputs: List<TensorBuffer> = emptyList()
            var outputs: List<TensorBuffer> = emptyList()

            var resultMessage: String

            try {
                environment = Tasks.await(
                    LiteRt.initialize(applicationContext)
                )

                model = CompiledModel.create(
                    assets,
                    modelFile,
                    CompiledModel.Options(Accelerator.CPU),
                    environment
                )

                inputs = model.createInputBuffers()
                outputs = model.createOutputBuffers()

                if (inputs.size != 1 || outputs.size != 1) {
                    throw Exception("Unexpected model tensor count")
                }

                val testInput = FloatArray(INPUT_ELEMENTS) { 0.5f }

                inputs[0].writeFloat(testInput)
                model.run(inputs, outputs)

                val testOutput = outputs[0].readFloat()

                if (testOutput.size != OUTPUT_ELEMENTS) {
                    throw Exception("Unexpected output size")
                }

                if (testOutput.any {
                    it.isNaN() || it.isInfinite()
                }) {
                    throw Exception("Invalid model output")
                }

                resultMessage =
                    "AI MODEL TEST PASSED!\n" +
                    "Real inference completed successfully.\n" +
                    "Input: 128 × 128 RGB\n" +
                    "Output: 512 × 512 RGB\n" +
                    "4× AI upscaling is ready."

            } catch (e: Exception) {
                resultMessage =
                    "AI model test failed:\n${e.message}"

            } finally {
                inputs.forEach {
                    try { it.close() } catch (_: Exception) {}
                }

                outputs.forEach {
                    try { it.close() } catch (_: Exception) {}
                }

                try { model?.close() } catch (_: Exception) {}
                try { environment?.close() } catch (_: Exception) {}
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                isModelTesting = false
                setControlsEnabled(true)
                statusText.text = resultMessage
            }
        }
    }

    private data class EditSettings(
        var brightness: Float = 0f,
        var contrast: Float = 1f,
        var saturation: Float = 1f,
        var warmth: Float = 0f,
        var sharpen: Float = 0.4f,
        var grayscale: Boolean = false
    )

    private fun interpretInstructions(
        strength: String,
        instructions: String
    ): EditSettings {

        val settings = EditSettings()

        when (strength) {
            "Low" -> {
                settings.contrast = 1.04f
                settings.saturation = 1.02f
                settings.sharpen = 0.25f
            }

            "Balanced" -> {
                settings.contrast = 1.08f
                settings.saturation = 1.06f
                settings.sharpen = 0.45f
            }

            "High" -> {
                settings.contrast = 1.13f
                settings.saturation = 1.10f
                settings.sharpen = 0.65f
            }

            "Ultra" -> {
                settings.contrast = 1.18f
                settings.saturation = 1.14f
                settings.sharpen = 0.85f
            }
        }

        val text = instructions.lowercase()

        if (
            text.contains("brighter") ||
            text.contains("brighten") ||
            text.contains("more light") ||
            text.contains("increase brightness")
        ) {
            settings.brightness += 0.12f
        }

        if (
            text.contains("darker") ||
            text.contains("darken") ||
            text.contains("less light")
        ) {
            settings.brightness -= 0.12f
        }

        if (
            text.contains("more contrast") ||
            text.contains("increase contrast")
        ) {
            settings.contrast += 0.12f
        }

        if (
            text.contains("less contrast") ||
            text.contains("softer contrast")
        ) {
            settings.contrast -= 0.10f
        }

        if (
            text.contains("vivid") ||
            text.contains("more colorful") ||
            text.contains("more colour") ||
            text.contains("more color") ||
            text.contains("increase saturation")
        ) {
            settings.saturation += 0.20f
        }

        if (
            text.contains("less saturation") ||
            text.contains("muted colors") ||
            text.contains("muted colours")
        ) {
            settings.saturation -= 0.20f
        }

        if (
            text.contains("warmer") ||
            text.contains("warm tones") ||
            text.contains("warm lighting")
        ) {
            settings.warmth += 0.10f
        }

        if (
            text.contains("cooler") ||
            text.contains("cool tones") ||
            text.contains("cool lighting")
        ) {
            settings.warmth -= 0.10f
        }

        if (
            text.contains("sharper") ||
            text.contains("sharpen") ||
            text.contains("more detail") ||
            text.contains("crisper")
        ) {
            settings.sharpen += 0.30f
        }

        if (
            text.contains("softer") ||
            text.contains("less sharp") ||
            text.contains("reduce sharpness")
        ) {
            settings.sharpen -= 0.25f
        }

        if (
            text.contains("no sharpening") ||
            text.contains("disable sharpening")
        ) {
            settings.sharpen = 0f
        }

        if (
            text.contains("black and white") ||
            text.contains("black & white") ||
            text.contains("grayscale") ||
            text.contains("greyscale")
        ) {
            settings.grayscale = true
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
            settings.sharpen.coerceIn(0f, 1.2f)

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

        source.getPixels(
            current, 0, width, 0, 0, width, 1
        )

        if (height > 1) {
            source.getPixels(
                next, 0, width, 0, 1, width, 1
            )
        } else {
            next.indices.forEach { next[it] = current[it] }
        }

        previous.indices.forEach { previous[it] = current[it] }

        for (y in 0 until height) {

            if (y > 0) {
                current.copyInto(previous)

                if (y < height - 1) {
                    source.getPixels(
                        next, 0, width, 0, y + 1, width, 1
                    )
                } else {
                    current.copyInto(next)
                }
            }

            for (x in 0 until width) {

                val center = current[x]

                val centerR = Color.red(center).toFloat()
                val centerG = Color.green(center).toFloat()
                val centerB = Color.blue(center).toFloat()

                val left = current[(x - 1).coerceAtLeast(0)]
                val right = current[(x + 1).coerceAtMost(width - 1)]

                val top = previous[x]
                val bottom = next[x]

                val neighborsR = (
                    Color.red(left) +
                    Color.red(right) +
                    Color.red(top) +
                    Color.red(bottom)
                ) / 4f

                val neighborsG = (
                    Color.green(left) +
                    Color.green(right) +
                    Color.green(top) +
                    Color.green(bottom)
                ) / 4f

                val neighborsB = (
                    Color.blue(left) +
                    Color.blue(right) +
                    Color.blue(top) +
                    Color.blue(bottom)
                ) / 4f

                var r = centerR +
                    settings.sharpen * (centerR - neighborsR)

                var g = centerG +
                    settings.sharpen * (centerG - neighborsG)

                var b = centerB +
                    settings.sharpen * (centerB - neighborsB)

                r = (r - 127.5f) * settings.contrast +
                    127.5f + settings.brightness * 255f

                g = (g - 127.5f) * settings.contrast +
                    127.5f + settings.brightness * 255f

                b = (b - 127.5f) * settings.contrast +
                    127.5f + settings.brightness * 255f

                r += settings.warmth * 255f
                b -= settings.warmth * 255f

                val gray =
                    0.299f * r +
                    0.587f * g +
                    0.114f * b

                r = gray + (r - gray) * settings.saturation
                g = gray + (g - gray) * settings.saturation
                b = gray + (b - gray) * settings.saturation

                if (settings.grayscale) {
                    r = gray
                    g = gray
                    b = gray
                }

                outputRow[x] = Color.rgb(
                    r.roundToInt().coerceIn(0, 255),
                    g.roundToInt().coerceIn(0, 255),
                    b.roundToInt().coerceIn(0, 255)
                )
            }

            output.setPixels(
                outputRow,
                0,
                width,
                0,
                y,
                width,
                1
            )
        }

        return output
    }

    private fun createTileStarts(length: Int): List<Int> {
        if (length <= TILE_SIZE) return listOf(0)

        val starts = mutableListOf(0)

        while (true) {
            val next = starts.last() + TILE_STRIDE

            if (next + TILE_SIZE >= length) {
                val finalStart =
                    (length - TILE_SIZE).coerceAtLeast(0)

                if (finalStart > starts.last()) {
                    starts.add(finalStart)
                }
                break
            }

            starts.add(next)
        }

        return starts
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
                LiteRt.initialize(applicationContext)
            )

            model = CompiledModel.create(
                assets,
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
                sourcePixels,
                0,
                width,
                0,
                0,
                width,
                height
            )

            val inputData = FloatArray(INPUT_ELEMENTS)

            val xStarts = createTileStarts(width)
            val yStarts = createTileStarts(height)

            val totalTiles = xStarts.size * yStarts.size
            var tileNumber = 0

            val contrast = when (strength) {
                "Low" -> 1.00f
                "High" -> 1.08f
                "Ultra" -> 1.12f
                else -> 1.04f
            }

            val saturation = when (strength) {
                "Low" -> 1.00f
                "High" -> 1.08f
                "Ultra" -> 1.12f
                else -> 1.04f
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
                                0.299f * r +
                                0.587f * g +
                                0.114f * b

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

                            val r = (
                                aiOutput[index] * 255f
                            ).roundToInt().coerceIn(0, 255)

                            val g = (
                                aiOutput[index + 1] * 255f
                            ).roundToInt().coerceIn(0, 255)

                            val b = (
                                aiOutput[index + 2] * 255f
                            ).roundToInt().coerceIn(0, 255)

                            tilePixels[
                                y * OUTPUT_TILE_SIZE + x
                            ] = Color.rgb(r, g, b)
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

    private fun enhancePhoto(uri: Uri) {
        if (isProcessing || isSaving || isModelTesting) return

        isProcessing = true

        val strength = selectedStrength
        val instructions = instructionsInput.text.toString().trim()

        setControlsEnabled(false)
        statusText.text = "Preparing photo for AI enhancement..."

        executor.execute {
            var aiBitmap: Bitmap? = null
            var finalBitmap: Bitmap? = null

            try {
                val bytes = readPhotoBytes(uri)

                val bounds = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }

                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, bounds
                )

                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw Exception("Invalid photo")
                }

                var sample = 1

                while (
                    bounds.outWidth / sample > MAX_INPUT_DIMENSION ||
                    bounds.outHeight / sample > MAX_INPUT_DIMENSION
                ) {
                    sample *= 2
                }

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }

                val source = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, options
                ) ?: throw Exception("Could not decode photo")

                try {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            statusText.text =
                                "Running Real-ESRGAN AI enhancement..."
                        }
                    }

                    aiBitmap = runRealEsrgan(source, strength) {
                        completed, total ->

                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                statusText.text =
                                    "AI processing tile $completed of $total..."
                            }
                        }
                    }

                } finally {
                    source.recycle()
                }

                val settings = interpretInstructions(
                    strength,
                    instructions
                )

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        statusText.text =
                            "Applying your editing instructions..."
                    }
                }

                val aiResult = aiBitmap
                    ?: throw Exception("AI did not produce an image")

                finalBitmap = applyLocalEditing(
                    aiResult,
                    settings
                )

                aiResult.recycle()
                aiBitmap = null

                val completed = finalBitmap
                    ?: throw Exception("Editing failed")

                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        completed.recycle()
                        return@runOnUiThread
                    }

                    enhancedBitmap?.let {
                        if (!it.isRecycled) it.recycle()
                    }

                    enhancedBitmap = completed
                    finalBitmap = null
                    isShowingEnhanced = true

                    displayCurrentPhoto()

                    isProcessing = false
                    setControlsEnabled(true)

                    statusText.text =
                        "Enhancement complete! 4× AI upscale + offline editing ($strength)."

                    Toast.makeText(
                        this,
                        "Enhanced photo is ready!",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                aiBitmap?.let {
                    if (!it.isRecycled) it.recycle()
                }

                finalBitmap?.let {
                    if (!it.isRecycled) it.recycle()
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    isProcessing = false
                    setControlsEnabled(true)

                    statusText.text =
                        "Enhancement failed: ${e.message}"

                    Toast.makeText(
                        this,
                        "Enhancement failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun saveEnhancedPhoto() {
        val bitmap = enhancedBitmap

        if (bitmap == null || bitmap.isRecycled) {
            Toast.makeText(
                this,
                "Enhance a photo first",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (isProcessing || isSaving || isModelTesting) return

        isSaving = true
        setControlsEnabled(false)

        statusText.text = "Saving enhanced photo..."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            executor.execute {
                var savedUri: Uri? = null

                try {
                    val values = ContentValues().apply {
                        put(
                            MediaStore.Images.Media.DISPLAY_NAME,
                            "Enhanced_${System.currentTimeMillis()}.jpg"
                        )

                        put(
                            MediaStore.Images.Media.MIME_TYPE,
                            "image/jpeg"
                        )

                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES +
                                "/Private Photo Enhancer"
                        )

                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }

                    savedUri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values
                    ) ?: throw Exception("Could not create photo")

                    val output = contentResolver.openOutputStream(
                        savedUri, "w"
                    ) ?: throw Exception("Could not open output")

                    val success = output.use {
                        bitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            95,
                            it
                        )
                    }

                    if (!success) {
                        throw Exception("Could not write photo")
                    }

                    val finished = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }

                    contentResolver.update(
                        savedUri, finished, null, null
                    )

                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread

                        isSaving = false
                        setControlsEnabled(true)

                        statusText.text =
                            "Photo saved to your gallery!"

                        Toast.makeText(
                            this,
                            "Photo saved successfully!",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                } catch (e: Exception) {
                    savedUri?.let {
                        contentResolver.delete(it, null, null)
                    }

                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread

                        isSaving = false
                        setControlsEnabled(true)
                        statusText.text = "Save failed: ${e.message}"
                    }
                }
            }

        } else {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/jpeg"
                putExtra(Intent.EXTRA_TITLE, "Enhanced_Photo.jpg")
            }

            startActivityForResult(intent, SAVE_DOCUMENT)
        }
    }

    private fun saveBitmapToUri(uri: Uri) {
        val bitmap = enhancedBitmap

        if (bitmap == null || bitmap.isRecycled) {
            isSaving = false
            setControlsEnabled(true)
            return
        }

        executor.execute {
            try {
                val output = contentResolver.openOutputStream(
                    uri, "w"
                ) ?: throw Exception("Could not open output")

                val success = output.use {
                    bitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        95,
                        it
                    )
                }

                if (!success) {
                    throw Exception("Could not write photo")
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    isSaving = false
                    setControlsEnabled(true)
                    statusText.text = "Photo saved successfully!"

                    Toast.makeText(
                        this,
                        "Enhanced photo saved!",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    isSaving = false
                    setControlsEnabled(true)
                    statusText.text = "Save failed: ${e.message}"
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return

            try {
                val flags =
                    data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION

                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
            }

            selectedImage = uri

            enhancedBitmap?.let {
                if (!it.isRecycled) it.recycle()
            }

            enhancedBitmap = null
            isShowingEnhanced = false

            photoView.setImageDrawable(null)
            photoView.setImageURI(uri)
            photoView.resetZoom()

            compareButton.text = "View Original Photo"
            compareButton.isEnabled = false
            saveButton.isEnabled = false

            statusText.text =
                "Original photo loaded. Add editing instructions or use the default enhancement!"

            return
        }

        if (requestCode == SAVE_DOCUMENT) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data

                if (uri != null) {
                    saveBitmapToUri(uri)
                } else {
                    isSaving = false
                    setControlsEnabled(true)
                    statusText.text = "No save location selected"
                }
            } else {
                isSaving = false
                setControlsEnabled(true)
                statusText.text = "Save cancelled"
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()

        if (::photoView.isInitialized) {
            photoView.setImageDrawable(null)
        }

        enhancedBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }

        enhancedBitmap = null

        super.onDestroy()
    }
}

class ZoomableImageView(
    context: android.content.Context
) : ImageView(context) {

    private val displayMatrix = Matrix()

    private var currentScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var scaling = false

    private val maximumZoom = 6f

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

                override fun onScaleBegin(
                    detector: ScaleGestureDetector
                ): Boolean {
                    scaling = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                override fun onScale(
                    detector: ScaleGestureDetector
                ): Boolean {
                    val newScale =
                        (currentScale * detector.scaleFactor)
                            .coerceIn(1f, maximumZoom)

                    val actualFactor = newScale / currentScale

                    displayMatrix.postScale(
                        actualFactor,
                        actualFactor,
                        detector.focusX,
                        detector.focusY
                    )

                    currentScale = newScale
                    limitMovement()
                    imageMatrix = displayMatrix

                    return true
                }

                override fun onScaleEnd(
                    detector: ScaleGestureDetector
                ) {
                    scaling = false
                }
            }
        )

    private val gestureDetector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {

                override fun onDown(e: MotionEvent): Boolean = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (currentScale > 1.1f) {
                        resetZoom()
                    } else {
                        displayMatrix.postScale(
                            2.5f, 2.5f, e.x, e.y
                        )

                        currentScale = 2.5f
                        limitMovement()
                        imageMatrix = displayMatrix
                    }

                    return true
                }
            }
        )

    init {
        scaleType = ScaleType.MATRIX
        adjustViewBounds = false
        isClickable = true
    }

    fun resetZoom() {
        currentScale = 1f
        scaling = false
        fitImage()
    }

    private fun fitImage() {
        val drawable = drawable ?: return

        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()

        if (
            imageWidth <= 0f ||
            imageHeight <= 0f ||
            width <= 0 ||
            height <= 0
        ) return

        val fitScale = minOf(
            width.toFloat() / imageWidth,
            height.toFloat() / imageHeight
        )

        val left = (width - imageWidth * fitScale) / 2f
        val top = (height - imageHeight * fitScale) / 2f

        displayMatrix.reset()
        displayMatrix.setScale(fitScale, fitScale)
        displayMatrix.postTranslate(left, top)

        currentScale = 1f
        imageMatrix = displayMatrix
    }

    private fun limitMovement() {
        val drawable = drawable ?: return

        val rect = RectF(
            0f,
            0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat()
        )

        displayMatrix.mapRect(rect)

        var dx = 0f
        var dy = 0f

        if (rect.width() <= width) {
            dx = (width - rect.width()) / 2f - rect.left
        } else if (rect.left > 0f) {
            dx = -rect.left
        } else if (rect.right < width) {
            dx = width - rect.right
        }

        if (rect.height() <= height) {
            dy = (height - rect.height()) / 2f - rect.top
        } else if (rect.top > 0f) {
            dy = -rect.top
        } else if (rect.bottom < height) {
            dy = height - rect.bottom
        }

        displayMatrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y

                parent?.requestDisallowInterceptTouchEvent(
                    currentScale > 1f
                )
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && !scaling) {
                    if (currentScale > 1.01f) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY

                        displayMatrix.postTranslate(dx, dy)
                        limitMovement()
                        imageMatrix = displayMatrix

                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }

                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                scaling = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitImage()
    }
}
