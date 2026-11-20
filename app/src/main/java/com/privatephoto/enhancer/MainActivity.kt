
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
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment as LiteRtEnvironment
import com.google.ai.edge.litert.TensorBuffer
import com.google.android.gms.litert.LiteRt
import com.google.android.gms.tasks.Tasks

import java.util.concurrent.Executors
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var photoView: ZoomableImageView
    private lateinit var compareButton: Button
    private lateinit var strengthText: TextView
    private lateinit var modeText: TextView
    private lateinit var statusText: TextView
    private lateinit var enhanceButton: Button
    private lateinit var saveButton: Button
    private lateinit var uploadButton: Button
    private lateinit var testAiButton: Button
    private lateinit var downloadModelButton: Button
    private lateinit var instructionsInput: EditText

    private var selectedStrength = "Balanced"
    private var selectedMode = "Natural Restore"
    private var selectedImage: Uri? = null
    private var enhancedBitmap: Bitmap? = null
    private var isShowingEnhanced = false

    private val strengthButtons = mutableMapOf<String, Button>()
    private val modeButtons = mutableMapOf<String, Button>()
    private val executor = Executors.newSingleThreadExecutor()

    private var observedWorkId: UUID? = null
    private var observedWorkLiveData: LiveData<WorkInfo?>? = null
    private var observedWorkObserver: Observer<WorkInfo?>? = null
    private var completedWorkId: UUID? = null

    private val PICK_IMAGE = 100
    private val SAVE_DOCUMENT = 101

    private var isProcessing = false
    private var isSaving = false
    private var isModelTesting = false
    private var isDownloadingPhotoModel = false

    private val modelFile = "Real-ESRGAN-General-x4v3.tflite"
    private val photoQualityModelFile by lazy {
        File(filesDir, "realesrgan_x4plus_w16.onnx")
    }

    companion object {
        private const val PHOTO_MODEL_URL =
            "https://huggingface.co/openpixels-app/browser-models/resolve/e618190d21568175ea4d6d73a85dc599c4e36139/realesrgan_x4plus_w16.onnx"
        private const val PHOTO_MODEL_BYTES = 33_724_311L
        private const val PHOTO_MODEL_SHA256 =
            "29fbb9bc95708f9f63966804a389be77fe060dbdda882402bd1592a10908d364"

        private const val TILE_SIZE = 128
        private const val OUTPUT_TILE_SIZE = 512
        private const val CHANNELS = 3
        private const val TILE_STRIDE = 96

        private const val INPUT_ELEMENTS =
            TILE_SIZE * TILE_SIZE * CHANNELS

        private const val OUTPUT_ELEMENTS =
            OUTPUT_TILE_SIZE * OUTPUT_TILE_SIZE * CHANNELS

        private const val MAX_INPUT_DIMENSION = 768
        private const val WORK_PREFERENCES = "photo_enhancer_work"
        private const val PREF_LAST_WORK_ID = "last_enhancement_work_id"
        private const val PREF_IMAGE_URI = "last_image_uri"
        private const val PREF_STRENGTH = "last_strength"
        private const val PREF_MODE = "last_mode"
        private const val PREF_INSTRUCTIONS = "last_instructions"
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
            text = "Offline AI Enhancement • Photos stay on your phone"
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
            hint = "Example: Make the photo brighter, improve lighting, sharpen details naturally..."
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

        val modeLabel = TextView(this).apply {
            text = "Editing Mode"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        }
        layout.addView(modeLabel)

        modeText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.GREEN)
            gravity = Gravity.CENTER
        }
        layout.addView(modeText)

        val modeDescriptions = mapOf(
            "Natural Restore" to "Cleaner photos, gentler sharpening, reduced noise",
            "Balanced" to "A blend of cleanup and added detail",
            "Maximum Detail" to "Stronger sharpening and more visible texture"
        )

        modeDescriptions.keys.forEach { mode ->
            val button = Button(this).apply {
                text = mode
            }

            modeButtons[mode] = button
            layout.addView(button)

            button.setOnClickListener {
                if (!isProcessing && !isSaving && !isModelTesting) {
                    selectedMode = mode
                    updateModeButtons()
                }
            }
        }

        val strengthLabel = TextView(this).apply {
            text = "Enhancement Strength"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
        }
        layout.addView(strengthLabel)

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

        val privacyNote = TextView(this).apply {
            text = "Your photos are processed on this phone. Internet is used only to download the optional AI model."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        layout.addView(privacyNote)

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

        downloadModelButton = Button(this).apply {
            text = "Download High-Detail Model (34 MB)"
        }
        layout.addView(downloadModelButton)
        downloadModelButton.setOnClickListener {
            downloadPhotoQualityModel()
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
        updateModeButtons()
        updatePhotoModelButton()
        statusText.text = "Upload a photo to get started"
        restoreLatestEnhancement()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        enhanceButton.isEnabled = enabled
        uploadButton.isEnabled = enabled
        testAiButton.isEnabled = enabled
        downloadModelButton.isEnabled =
            enabled && !hasPhotoQualityModel() && !isDownloadingPhotoModel
        updatePhotoModelButton()
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

        modeButtons.values.forEach {
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

            button.text = if (selected) "✓ $strength" else strength
            button.setTextColor(
                if (selected) Color.GREEN else Color.WHITE
            )
        }
    }

    private fun updateModeButtons() {
        modeText.text = "Selected: $selectedMode"

        modeButtons.forEach { (mode, button) ->
            val selected = mode == selectedMode

            button.text = if (selected) "✓ $mode" else mode
            button.setTextColor(
                if (selected) Color.GREEN else Color.WHITE
            )
        }
    }

    private fun hasPhotoQualityModel(): Boolean =
        photoQualityModelFile.isFile &&
            photoQualityModelFile.length() == PHOTO_MODEL_BYTES

    private fun updatePhotoModelButton() {
        if (!::downloadModelButton.isInitialized) return
        downloadModelButton.text = if (hasPhotoQualityModel()) {
            "High-Detail Model Installed"
        } else {
            "Download High-Detail Model (34 MB)"
        }
        testAiButton.text = if (hasPhotoQualityModel()) {
            "Test High-Detail Model"
        } else {
            "Test AI Model"
        }
        downloadModelButton.isEnabled =
            !hasPhotoQualityModel() && !isDownloadingPhotoModel &&
                !isProcessing && !isSaving && !isModelTesting
    }

    private fun validatePhotoModel(file: File) {
        val environment = OrtEnvironment.getEnvironment()
        environment.setTelemetry(false)
        val options = OrtSession.SessionOptions()
        var session: OrtSession? = null
        var inputTensor: OnnxTensor? = null

        try {
            val activeSession = environment.createSession(file.absolutePath, options)
            session = activeSession
            val inputName = activeSession.inputNames.firstOrNull()
                ?: throw Exception("High-detail model has no input")
            val outputName = activeSession.outputNames.firstOrNull()
                ?: throw Exception("High-detail model has no output")

            val activeInput = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(FloatArray(INPUT_ELEMENTS) { 0.5f }),
                longArrayOf(1, 3, TILE_SIZE.toLong(), TILE_SIZE.toLong())
            )
            inputTensor = activeInput

            activeSession.run(mapOf(inputName to activeInput)).use { results ->
                val output = results.get(outputName).orElse(null) as? OnnxTensor
                    ?: throw Exception("High-detail model output is not a tensor")
                val values = output.floatBuffer
                if (values.remaining() != OUTPUT_ELEMENTS) {
                    throw Exception("High-detail model output shape is not 4×")
                }
                val sample = FloatArray(OUTPUT_ELEMENTS)
                values.get(sample)
                if (sample.any { it.isNaN() || it.isInfinite() }) {
                    throw Exception("High-detail model returned invalid values")
                }
            }
        } finally {
            try { inputTensor?.close() } catch (_: Exception) {}
            try { session?.close() } catch (_: Exception) {}
            try { options.close() } catch (_: Exception) {}
        }
    }

    private fun downloadPhotoQualityModel() {
        if (
            isDownloadingPhotoModel || isProcessing ||
            isSaving || isModelTesting || hasPhotoQualityModel()
        ) return

        isDownloadingPhotoModel = true
        setControlsEnabled(false)
        statusText.text =
            "Downloading the optional 34 MB high-detail model. Wi-Fi recommended..."

        executor.execute {
            val temporaryFile =
                File(filesDir, "realesrgan_x4plus_w16.onnx.part")
            var connection: HttpURLConnection? = null
            var resultMessage: String

            try {
                val activeConnection =
                    (URL(PHOTO_MODEL_URL).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 30_000
                        readTimeout = 120_000
                        requestMethod = "GET"
                        setRequestProperty(
                            "User-Agent",
                            "PrivatePhotoEnhancer/1.0"
                        )
                    }
                connection = activeConnection
                activeConnection.connect()

                if (activeConnection.responseCode !in 200..299) {
                    throw Exception(
                        "Model download failed (HTTP ${activeConnection.responseCode})"
                    )
                }

                var downloaded = 0L
                var lastProgressBucket = -1L

                activeConnection.inputStream.use { input ->
                    temporaryFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            if (downloaded > PHOTO_MODEL_BYTES) {
                                throw Exception("Downloaded model was larger than expected")
                            }
                            output.write(buffer, 0, count)

                            val bucket = downloaded / (3L * 1024L * 1024L)
                            if (bucket != lastProgressBucket) {
                                lastProgressBucket = bucket
                                val percent =
                                    (downloaded * 100L / PHOTO_MODEL_BYTES).toInt()
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed) {
                                        statusText.text =
                                            "Downloading high-detail model: $percent%"
                                    }
                                }
                            }
                        }
                    }
                }

                if (temporaryFile.length() != PHOTO_MODEL_BYTES) {
                    throw Exception("Downloaded model size did not match the verified file")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                temporaryFile.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }

                val actualHash = digest.digest().joinToString("") {
                    "%02x".format(it.toInt() and 0xff)
                }
                if (!actualHash.equals(PHOTO_MODEL_SHA256, ignoreCase = true)) {
                    throw Exception("Model checksum verification failed")
                }

                validatePhotoModel(temporaryFile)

                if (photoQualityModelFile.exists()) {
                    photoQualityModelFile.delete()
                }
                if (!temporaryFile.renameTo(photoQualityModelFile)) {
                    throw Exception("Could not install the downloaded model")
                }

                resultMessage =
                    "High-detail model installed and verified. New enhancements will use Real-ESRGAN x4plus."

            } catch (e: Exception) {
                temporaryFile.delete()
                resultMessage =
                    "Model download failed: ${e.message ?: "unknown error"}. Your existing model is still available."
            } finally {
                connection?.disconnect()
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                isDownloadingPhotoModel = false
                setControlsEnabled(true)
                updatePhotoModelButton()
                statusText.text = resultMessage
            }
        }
    }

    private fun testAiModel() {
        if (isModelTesting || isProcessing || isSaving) return

        if (hasPhotoQualityModel()) {
            isModelTesting = true
            setControlsEnabled(false)
            statusText.text = "Testing high-detail Real-ESRGAN x4plus model..."

            executor.execute {
                var resultMessage: String
                try {
                    validatePhotoModel(photoQualityModelFile)
                    resultMessage =
                        "HIGH-DETAIL MODEL TEST PASSED!\n" +
                        "Real-ESRGAN x4plus ONNX inference completed successfully.\n" +
                        "Input: 128 × 128 RGB\n" +
                        "Output: 512 × 512 RGB\n" +
                        "4× high-detail upscaling is ready."
                } catch (e: Exception) {
                    resultMessage =
                        "High-detail model test failed:\n${e.message}"
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    isModelTesting = false
                    setControlsEnabled(true)
                    statusText.text = resultMessage
                }
            }
            return
        }

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

                inputs[0].writeFloat(FloatArray(INPUT_ELEMENTS) { 0.5f })
                model.run(inputs, outputs)

                val testOutput = outputs[0].readFloat()

                if (testOutput.size != OUTPUT_ELEMENTS) {
                    throw Exception("Unexpected output size")
                }

                if (testOutput.any { it.isNaN() || it.isInfinite() }) {
                    throw Exception("Invalid model output")
                }

                resultMessage =
                    "AI MODEL TEST PASSED!\n" +
                    "Real inference completed successfully.\n" +
                    "Input: 128 × 128 RGB\n" +
                    "Output: 512 × 512 RGB\n" +
                    "4× AI upscaling is ready."

            } catch (e: Exception) {
                resultMessage = "AI model test failed:\n${e.message}"

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

    private fun enhancePhoto(uri: Uri) {
        if (isProcessing || isSaving || isModelTesting) return

        isProcessing = true
        setControlsEnabled(false)
        statusText.text = "Starting on-device AI enhancement..."

        val request = OneTimeWorkRequestBuilder<PhotoEnhancementWorker>()
            .setInputData(
                workDataOf(
                    PhotoEnhancementWorker.KEY_IMAGE_URI to uri.toString(),
                    PhotoEnhancementWorker.KEY_STRENGTH to selectedStrength,
                    PhotoEnhancementWorker.KEY_MODE to selectedMode,
                    PhotoEnhancementWorker.KEY_INSTRUCTIONS to
                        instructionsInput.text.toString().trim().take(2000)
                )
            )
            .build()

        try {
            clearEnhancementObserver()
            getSharedPreferences(WORK_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_WORK_ID, request.id.toString())
                .putString(PREF_IMAGE_URI, uri.toString())
                .putString(PREF_STRENGTH, selectedStrength)
                .putString(PREF_MODE, selectedMode)
                .putString(
                    PREF_INSTRUCTIONS,
                    instructionsInput.text.toString().trim().take(2000)
                )
                .apply()

            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                PhotoEnhancementWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )

            observeEnhancementWork(request.id)
        } catch (error: Exception) {
            getSharedPreferences(WORK_PREFERENCES, MODE_PRIVATE)
                .edit()
                .remove(PREF_LAST_WORK_ID)
                .remove(PREF_IMAGE_URI)
                .remove(PREF_STRENGTH)
                .remove(PREF_MODE)
                .remove(PREF_INSTRUCTIONS)
                .apply()
            isProcessing = false
            setControlsEnabled(true)
            statusText.text = "Could not start enhancement: ${error.message}"
        }
    }

    private fun restoreLatestEnhancement() {
        val savedId = getSharedPreferences(WORK_PREFERENCES, MODE_PRIVATE)
            .getString(PREF_LAST_WORK_ID, null) ?: return

        val workId = try {
            UUID.fromString(savedId)
        } catch (_: IllegalArgumentException) {
            getSharedPreferences(WORK_PREFERENCES, MODE_PRIVATE)
                .edit()
                .remove(PREF_LAST_WORK_ID)
                .apply()
            return
        }

        val preferences = getSharedPreferences(WORK_PREFERENCES, MODE_PRIVATE)
        selectedImage = preferences.getString(PREF_IMAGE_URI, null)?.let(Uri::parse)
        selectedStrength = preferences.getString(PREF_STRENGTH, "Balanced") ?: "Balanced"
        selectedMode = preferences.getString(PREF_MODE, "Natural Restore") ?: "Natural Restore"
        instructionsInput.setText(preferences.getString(PREF_INSTRUCTIONS, "").orEmpty())
        updateStrengthButtons()
        updateModeButtons()
        selectedImage?.let { photoView.setImageURI(it) }

        isProcessing = true
        setControlsEnabled(false)
        statusText.text = "Restoring the previous enhancement..."

        executor.execute {
            val info = try {
                WorkManager.getInstance(applicationContext)
                    .getWorkInfoById(workId)
                    .get()
            } catch (_: Exception) {
                null
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                if (info == null ||
                    info.state == WorkInfo.State.FAILED ||
                    info.state == WorkInfo.State.CANCELLED
                ) {
                    getSharedPreferences(WORK_PREFERENCES, MODE_PRIVATE)
                        .edit()
                        .remove(PREF_LAST_WORK_ID)
                        .apply()
                    isProcessing = false
                    setControlsEnabled(true)
                    statusText.text = "Upload a photo to get started"
                } else {
                    observeEnhancementWork(workId)
                }
            }
        }
    }

    private fun observeEnhancementWork(workId: UUID) {
        clearEnhancementObserver()

        val liveData = WorkManager.getInstance(applicationContext)
            .getWorkInfoByIdLiveData(workId)
        val observer = Observer<WorkInfo?> { info ->
            if (info != null) handleEnhancementWorkInfo(info)
        }

        observedWorkId = workId
        observedWorkLiveData = liveData
        observedWorkObserver = observer
        liveData.observeForever(observer)
    }

    private fun clearEnhancementObserver() {
        val liveData = observedWorkLiveData
        val observer = observedWorkObserver
        if (liveData != null && observer != null) {
            liveData.removeObserver(observer)
        }
        observedWorkId = null
        observedWorkLiveData = null
        observedWorkObserver = null
    }

    private fun handleEnhancementWorkInfo(info: WorkInfo) {
        val preferences = getSharedPreferences(WORK_PREFERENCES, MODE_PRIVATE)
        val imageUriText =
            info.progress.getString(PhotoEnhancementWorker.KEY_IMAGE_URI)
                ?: info.outputData.getString(PhotoEnhancementWorker.KEY_IMAGE_URI)
                ?: preferences.getString(PREF_IMAGE_URI, null)
        if (!imageUriText.isNullOrBlank() &&
            selectedImage?.toString() != imageUriText
        ) {
            selectedImage = Uri.parse(imageUriText)
            photoView.setImageURI(selectedImage)
            photoView.resetZoom()
        }

        when (info.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> {
                isProcessing = true
                setControlsEnabled(false)
                statusText.text = "Enhancement queued..."
            }

            WorkInfo.State.RUNNING -> {
                isProcessing = true
                setControlsEnabled(false)

                val progress = info.progress
                val stage = progress.getString(
                    PhotoEnhancementWorker.KEY_STAGE
                ) ?: "Preparing on-device enhancement..."

                val completed = progress.getInt(
                    PhotoEnhancementWorker.KEY_COMPLETED_TILES, 0
                )
                val total = progress.getInt(
                    PhotoEnhancementWorker.KEY_TOTAL_TILES, 0
                )
                val percent = progress.getInt(
                    PhotoEnhancementWorker.KEY_PERCENT, 0
                )
                val etaSeconds = progress.getLong(
                    PhotoEnhancementWorker.KEY_ETA_SECONDS, 0L
                )

                statusText.text = if (total > 0) {
                    val etaText = if (etaSeconds > 0L) {
                        val minutes = etaSeconds / 60L
                        val seconds = etaSeconds % 60L
                        if (minutes > 0L) {
                            " • about ${minutes}m ${seconds}s remaining"
                        } else {
                            " • about ${seconds}s remaining"
                        }
                    } else {
                        ""
                    }
                    "$stage: $completed/$total tiles ($percent%)$etaText"
                } else {
                    stage
                }
            }

            WorkInfo.State.SUCCEEDED -> {
                if (completedWorkId == info.id) return
                completedWorkId = info.id

                val outputPath = info.outputData.getString(
                    PhotoEnhancementWorker.KEY_OUTPUT_PATH
                )
                val restoredBitmap = outputPath?.let {
                    BitmapFactory.decodeFile(it)
                }

                if (restoredBitmap == null) {
                    isProcessing = false
                    setControlsEnabled(true)
                    statusText.text =
                        "Enhancement finished, but its saved result could not be opened."
                    getSharedPreferences(WORK_PREFERENCES, MODE_PRIVATE)
                        .edit()
                        .remove(PREF_LAST_WORK_ID)
                        .apply()
                    return
                }

                enhancedBitmap?.let {
                    if (!it.isRecycled) it.recycle()
                }
                enhancedBitmap = restoredBitmap

                selectedStrength = info.outputData.getString(
                    PhotoEnhancementWorker.KEY_STRENGTH
                ) ?: preferences.getString(PREF_STRENGTH, "Balanced") ?: "Balanced"
                selectedMode = info.outputData.getString(
                    PhotoEnhancementWorker.KEY_MODE
                ) ?: preferences.getString(PREF_MODE, "Natural Restore") ?: "Natural Restore"
                instructionsInput.setText(
                    info.outputData.getString(
                        PhotoEnhancementWorker.KEY_INSTRUCTIONS
                    ) ?: preferences.getString(PREF_INSTRUCTIONS, "").orEmpty()
                )

                updateStrengthButtons()
                updateModeButtons()
                isShowingEnhanced = true
                isProcessing = false
                displayCurrentPhoto()
                setControlsEnabled(true)

                statusText.text =
                    "Complete! 4× on-device AI upscale • $selectedMode • $selectedStrength strength."
            }

            WorkInfo.State.FAILED -> {
                isProcessing = false
                setControlsEnabled(true)
                val error = info.outputData.getString(
                    PhotoEnhancementWorker.KEY_ERROR
                ) ?: "Unknown enhancement error"
                statusText.text = "Enhancement failed: $error"
                getSharedPreferences(WORK_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .remove(PREF_LAST_WORK_ID)
                    .apply()
            }

            WorkInfo.State.CANCELLED -> {
                isProcessing = false
                setControlsEnabled(true)
                statusText.text = "Enhancement cancelled."
                getSharedPreferences(WORK_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .remove(PREF_LAST_WORK_ID)
                    .apply()
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
                        statusText.text = "Photo saved to your gallery!"

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

            clearEnhancementObserver()
            completedWorkId = null
            getSharedPreferences(WORK_PREFERENCES, MODE_PRIVATE)
                .edit()
                .remove(PREF_LAST_WORK_ID)
                .apply()

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
                "Original photo loaded. Choose an editing mode and strength, then enhance!"

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
        clearEnhancementObserver()
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
