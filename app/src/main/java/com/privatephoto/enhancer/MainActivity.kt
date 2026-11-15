
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

        val hint = TextView(this).apply {
            text = "Pinch to zoom • Drag to inspect • Double-tap to zoom"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        layout.addView(hint)

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

        val label = TextView(this).apply {
            text = "Enhancement Strength"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
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

    // REAL AI MODEL TEST

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
                    throw Exception(
                        "Unexpected model tensor count. " +
                            "Inputs: ${inputs.size}, outputs: ${outputs.size}"
                    )
                }

                val testInput = FloatArray(INPUT_ELEMENTS) {
                    0.5f
                }

                inputs[0].writeFloat(testInput)
                model.run(inputs, outputs)

                val testOutput = outputs[0].readFloat()

                if (testOutput.size != OUTPUT_ELEMENTS) {
                    throw Exception(
                        "Unexpected output size: ${testOutput.size}. " +
                            "Expected: $OUTPUT_ELEMENTS"
                    )
                }

                if (testOutput.any {
                    it.isNaN() || it.isInfinite()
                }) {
                    throw Exception("Model produced invalid output values")
                }

                resultMessage =
                    "AI MODEL TEST PASSED!\n" +
                    "Real inference completed successfully.\n" +
                    "Input: 128 × 128 RGB\n" +
                    "Output: 512 × 512 RGB\n" +
                    "4× AI upscaling is ready."

            } catch (e: Exception) {
                resultMessage =
                    "AI model test failed:\n" +
                    (e.message ?: e.javaClass.simpleName)

            } finally {
                inputs.forEach {
                    try {
                        it.close()
                    } catch (_: Exception) {
                    }
                }

                outputs.forEach {
                    try {
                        it.close()
                    } catch (_: Exception) {
                    }
                }

                try {
                    model?.close()
                } catch (_: Exception) {
                }

                try {
                    environment?.close()
                } catch (_: Exception) {
                }
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                isModelTesting = false
                setControlsEnabled(true)
                statusText.text = resultMessage
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        enhanceButton.isEnabled = enabled
        uploadButton.isEnabled = enabled
        testAiButton.isEnabled = enabled

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

    // DISPLAY PHOTO

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

    // REAL-ESRGAN PHOTO ENHANCEMENT

    private fun enhancePhoto(uri: Uri) {
        if (isProcessing || isSaving || isModelTesting) return

        isProcessing = true
        val strength = selectedStrength

        setControlsEnabled(false)
        statusText.text = "Preparing photo for AI enhancement..."

        executor.execute {
            var result: Bitmap? = null

            try {
                val bytes = readPhotoBytes(uri)

                val bounds = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }

                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    bounds
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
                    bytes,
                    0,
                    bytes.size,
                    options
                ) ?: throw Exception("Could not decode photo")

                try {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            statusText.text =
                                "Running overlapping Real-ESRGAN AI tiles..."
                        }
                    }

                    result = runRealEsrgan(source, strength) {
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

                val completed = result
                    ?: throw Exception("AI did not produce an image")

                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        completed.recycle()
                        return@runOnUiThread
                    }

                    enhancedBitmap?.let {
                        if (!it.isRecycled) {
                            it.recycle()
                        }
                    }

                    enhancedBitmap = completed
                    isShowingEnhanced = true

                    displayCurrentPhoto()

                    isProcessing = false
                    setControlsEnabled(true)

                    statusText.text =
                        "AI enhancement complete! 4× upscale ($strength)."

                    Toast.makeText(
                        this,
                        "AI enhanced photo is ready!",
                        Toast.LENGTH_LONG
                    ).show()
                }

                result = null

            } catch (e: Exception) {
                result?.let {
                    if (!it.isRecycled) {
                        it.recycle()
                    }
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    isProcessing = false
                    setControlsEnabled(true)

                    statusText.text =
                        "AI enhancement failed: ${e.message}"

                    Toast.makeText(
                        this,
                        "AI enhancement failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // CREATE OVERLAPPING TILE POSITIONS

    private fun createTileStarts(length: Int): List<Int> {
        if (length <= TILE_SIZE) {
            return listOf(0)
        }

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

    // REAL-ESRGAN OVERLAPPING TILE INFERENCE ENGINE
    //
    // Input: 128 × 128 × 3 RGB float
    // Output: 512 × 512 × 3 RGB float
    //
    // Tiles overlap by 32 input pixels.
    // Only the central, non-overlapping portions are copied.
    // This helps reduce visible tile boundaries.

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

            val outputWidth = width * 4
            val outputHeight = height * 4

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

            val destination = Bitmap.createBitmap(
                outputWidth,
                outputHeight,
                Bitmap.Config.ARGB_8888
            )

            resultBitmap = destination

            val inputData = FloatArray(INPUT_ELEMENTS)

            val xStarts = createTileStarts(width)
            val yStarts = createTileStarts(height)

            val totalTiles = xStarts.size * yStarts.size
            var tileNumber = 0

            val contrast = when (strength) {
                "Low" -> 0.98f
                "High" -> 1.06f
                "Ultra" -> 1.09f
                else -> 1.02f
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

                    // Prepare the 128 × 128 RGB input tile.

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

                            // Apply selected strength before inference.

                            r = (
                                (r - 0.5f) * contrast + 0.5f
                            ).coerceIn(0f, 1f)

                            g = (
                                (g - 0.5f) * contrast + 0.5f
                            ).coerceIn(0f, 1f)

                            b = (
                                (b - 0.5f) * contrast + 0.5f
                            ).coerceIn(0f, 1f)

                            val gray =
                                0.299f * r +
                                0.587f * g +
                                0.114f * b

                            r = (
                                gray + (r - gray) * saturation
                            ).coerceIn(0f, 1f)

                            g = (
                                gray + (g - gray) * saturation
                            ).coerceIn(0f, 1f)

                            b = (
                                gray + (b - gray) * saturation
                            ).coerceIn(0f, 1f)

                            val index =
                                (y * TILE_SIZE + x) * CHANNELS

                            inputData[index] = r
                            inputData[index + 1] = g
                            inputData[index + 2] = b
                        }
                    }

                    // Run AI inference.

                    inputBuffer.writeFloat(inputData)
                    model.run(inputBuffers, outputBuffers)

                    val aiOutput = outputBuffer.readFloat()

                    if (aiOutput.size != OUTPUT_ELEMENTS) {
                        throw Exception(
                            "Unexpected AI output size: ${aiOutput.size}. " +
                                "Expected: $OUTPUT_ELEMENTS"
                        )
                    }

                    // Convert model output into bitmap pixels.

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

                    // Calculate crop boundaries between overlapping tiles.
                    // This keeps each output area from being written twice.

                    val cropLeft = if (xIndex == 0) {
                        0
                    } else {
                        (
                            xStarts[xIndex - 1] + TILE_SIZE - tileX
                        ) / 2
                    }.coerceIn(0, TILE_SIZE)

                    val cropTop = if (yIndex == 0) {
                        0
                    } else {
                        (
                            yStarts[yIndex - 1] + TILE_SIZE - tileY
                        ) / 2
                    }.coerceIn(0, TILE_SIZE)

                    val cropRight = if (xIndex == xStarts.lastIndex) {
                        minOf(TILE_SIZE, width - tileX)
                    } else {
                        (
                            tileX + TILE_SIZE + xStarts[xIndex + 1]
                        ) / 2 - tileX
                    }.coerceIn(cropLeft, TILE_SIZE)

                    val cropBottom = if (yIndex == yStarts.lastIndex) {
                        minOf(TILE_SIZE, height - tileY)
                    } else {
                        (
                            tileY + TILE_SIZE + yStarts[yIndex + 1]
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
                if (!it.isRecycled) {
                    it.recycle()
                }
            }

            throw e

        } finally {
            inputBuffers.forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }

            outputBuffers.forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }

            try {
                model?.close()
            } catch (_: Exception) {
            }

            try {
                environment?.close()
            } catch (_: Exception) {
            }
        }
    }

    // SAVE ENHANCED PHOTO

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

                        put(
                            MediaStore.Images.Media.IS_PENDING,
                            1
                        )
                    }

                    savedUri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values
                    ) ?: throw Exception("Could not create photo")

                    val output = contentResolver.openOutputStream(
                        savedUri,
                        "w"
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
                        put(
                            MediaStore.Images.Media.IS_PENDING,
                            0
                        )
                    }

                    contentResolver.update(
                        savedUri,
                        finished,
                        null,
                        null
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

                        statusText.text =
                            "Save failed: ${e.message}"
                    }
                }
            }

        } else {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/jpeg"

                putExtra(
                    Intent.EXTRA_TITLE,
                    "Enhanced_Photo.jpg"
                )
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
                    uri,
                    "w"
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

                    statusText.text =
                        "Save failed: ${e.message}"
                }
            }
        }
    }

    // PHOTO UPLOAD AND SAVE RESULT HANDLING

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return

            try {
                val flags =
                    data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION

                contentResolver.takePersistableUriPermission(
                    uri,
                    flags
                )
            } catch (_: Exception) {
            }

            selectedImage = uri

            enhancedBitmap?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
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
                "Original photo loaded. Ready for AI enhancement!"

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
            if (!it.isRecycled) {
                it.recycle()
            }
        }

        enhancedBitmap = null

        super.onDestroy()
    }
}

// ============================================
// ZOOMABLE IMAGE VIEW
// ============================================

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
                            2.5f,
                            2.5f,
                            e.x,
                            e.y
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