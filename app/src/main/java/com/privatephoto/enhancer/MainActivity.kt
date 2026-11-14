
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

    private val tileSize = 128
    private val outputTileSize = 512

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

        listOf("Low", "Balanced", "High").forEach { strength ->
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

            // FIX: var allows assignment in both try and catch.
            var resultMessage: String = ""

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

                if (inputs.isEmpty() || outputs.isEmpty()) {
                    throw Exception("Model has no input or output tensors")
                }

                resultMessage =
                    "AI MODEL LOADED SUCCESSFULLY! Ready for AI enhancement."

            } catch (e: Exception) {
                resultMessage =
                    "AI model test failed: ${e.message ?: e.javaClass.simpleName}"

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

    private fun setControlsEnabled(enabled: Boolean) {
        enhanceButton.isEnabled = enabled
        uploadButton.isEnabled = enabled
        testAiButton.isEnabled = enabled
        saveButton.isEnabled =
            enabled && enhancedBitmap != null &&
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
            button.text = if (selected) "✓ $strength" else strength
            button.setTextColor(
                if (selected) Color.GREEN else Color.WHITE
            )
        }
    }

    private fun readPhotoBytes(uri: Uri): ByteArray {
        val stream = contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot open selected photo")

        return stream.use { it.readBytes() }
    }

    // ACTUAL REAL-ESRGAN PHOTO ENHANCEMENT

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
                    bytes, 0, bytes.size, bounds
                )

                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw Exception("Invalid photo")
                }

                var sample = 1

                while (
                    bounds.outWidth / sample > 768 ||
                    bounds.outHeight / sample > 768
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

                    result = runRealEsrgan(source, strength) { completed, total ->
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
                        if (!it.isRecycled) it.recycle()
                    }

                    enhancedBitmap = completed
                    isShowingEnhanced = true

                    displayCurrentPhoto()

                    isProcessing = false
                    setControlsEnabled(true)

                    statusText.text =
                        "Real-ESRGAN AI enhancement complete! 4× upscale ($strength)"

                    Toast.makeText(
                        this,
                        "AI enhanced photo is ready!",
                        Toast.LENGTH_LONG
                    ).show()
                }

                result = null

            } catch (e: Exception) {
                result?.let {
                    if (!it.isRecycled) it.recycle()
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

    // REAL-ESRGAN TILE INFERENCE ENGINE

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

            if (inputBuffers.isEmpty() || outputBuffers.isEmpty()) {
                throw Exception("Missing AI model tensors")
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

            resultBitmap = Bitmap.createBitmap(
                outputWidth,
                outputHeight,
                Bitmap.Config.ARGB_8888
            )

            val inputData = FloatArray(
                3 * tileSize * tileSize
            )

            val expectedOutputSize =
                3 * outputTileSize * outputTileSize

            val totalTiles =
                ((width + tileSize - 1) / tileSize) *
                ((height + tileSize - 1) / tileSize)

            var tileNumber = 0

            val contrast = when (strength) {
                "Low" -> 0.98f
                "High" -> 1.06f
                else -> 1.02f
            }

            val saturation = when (strength) {
                "Low" -> 1.00f
                "High" -> 1.08f
                else -> 1.04f
            }

            for (tileY in 0 until height step tileSize) {
                for (tileX in 0 until width step tileSize) {

                    for (y in 0 until tileSize) {
                        for (x in 0 until tileSize) {

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

                            val index = y * tileSize + x

                            inputData[index] = r

                            inputData[
                                tileSize * tileSize + index
                            ] = g

                            inputData[
                                2 * tileSize * tileSize + index
                            ] = b
                        }
                    }

                    inputBuffer.writeFloat(inputData)

                    model.run(inputBuffers, outputBuffers)

                    val aiOutput = outputBuffer.readFloat()

                    if (aiOutput.size != expectedOutputSize) {
                        throw Exception(
                            "Unexpected AI output size: ${aiOutput.size}"
                        )
                    }

                    val tilePixels = IntArray(
                        outputTileSize * outputTileSize
                    )

                    val planeSize =
                        outputTileSize * outputTileSize

                    for (y in 0 until outputTileSize) {
                        for (x in 0 until outputTileSize) {

                            val index = y * outputTileSize + x

                            val r = (
                                aiOutput[index] * 255f
                            ).roundToInt().coerceIn(0, 255)

                            val g = (
                                aiOutput[planeSize + index] * 255f
                            ).roundToInt().coerceIn(0, 255)

                            val b = (
                                aiOutput[2 * planeSize + index] * 255f
                            ).roundToInt().coerceIn(0, 255)

                            tilePixels[index] =
                                Color.rgb(r, g, b)
                        }
                    }

                    val validWidth =
                        minOf(tileSize, width - tileX) * 4

                    val validHeight =
                        minOf(tileSize, height - tileY) * 4

                    resultBitmap.setPixels(
                        tilePixels,
                        0,
                        outputTileSize,
                        tileX * 4,
                        tileY * 4,
                        validWidth,
                        validHeight
                    )

                    tileNumber++
                    progress(tileNumber, totalTiles)
                }
            }

            return resultBitmap

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

        photoView.setImageDrawable(null)

        enhancedBitmap?.let {
            if (!it.isRecycled) it.recycle()
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
