
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        private const val SERVER = "https://photoenhancer.onrender.com"
        private const val PICK_IMAGE = 100
        private const val SAVE_DOCUMENT = 101
        private const val MAX_UPLOAD_SIZE = 20 * 1024 * 1024
    }

    private lateinit var photoView: ZoomableImageView
    private lateinit var compareButton: Button
    private lateinit var strengthText: TextView
    private lateinit var statusMessageView: TextView
    private lateinit var enhanceButton: Button
    private lateinit var saveButton: Button
    private lateinit var uploadButton: Button
    private lateinit var testAiButton: Button
    private lateinit var promptInput: EditText

    private var selectedStrength = "Balanced"
    private var selectedImage: Uri? = null
    private var enhancedBitmap: Bitmap? = null
    private var isShowingEnhanced = false

    private val strengthButtons = mutableMapOf<String, Button>()
    private val executor = Executors.newSingleThreadExecutor()

    private var isProcessing = false
    private var isSaving = false
    private var isTesting = false

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

        val subtitleText = TextView(this).apply {
            text = "Cloud AI Enhancement • Pinch to zoom • Drag to inspect"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        layout.addView(subtitleText)

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
            if (!isBusy() && enhancedBitmap != null) {
                isShowingEnhanced = !isShowingEnhanced
                displayCurrentPhoto()
            }
        }

        uploadButton = Button(this).apply {
            text = "Upload Picture"
        }
        layout.addView(uploadButton)

        uploadButton.setOnClickListener {
            if (!isBusy()) {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(intent, PICK_IMAGE)
            }
        }

        val strengthLabel = TextView(this).apply {
            text = "Enhancement Strength"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
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
                if (!isBusy()) {
                    selectedStrength = strength
                    updateStrengthButtons()
                }
            }
        }

        val promptLabel = TextView(this).apply {
            text = "Custom Editing Instructions"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(6))
        }
        layout.addView(promptLabel)

        promptInput = EditText(this).apply {
            this.hint = "Example: Improve lighting, sharpen details, reduce noise..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 16f
            minLines = 3
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(45, 45, 45))
        }

        layout.addView(
            promptInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(12))
            }
        )

        enhanceButton = Button(this).apply {
            text = "Enhance Photo with Cloud AI"
        }
        layout.addView(enhanceButton)

        statusMessageView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        layout.addView(statusMessageView)

        testAiButton = Button(this).apply {
            text = "Test Cloud AI Connection"
        }
        layout.addView(testAiButton)

        testAiButton.setOnClickListener {
            testCloudConnection()
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
            } else if (!isBusy()) {
                enhancePhoto(uri)
            }
        }

        saveButton.setOnClickListener {
            if (!isBusy()) {
                saveEnhancedPhoto()
            }
        }

        scroll.addView(layout)
        setContentView(scroll)

        updateStrengthButtons()
        statusMessageView.text = "Upload a photo to get started"
    }

    private fun isBusy(): Boolean =
        isProcessing || isSaving || isTesting

    private fun setControlsEnabled(enabled: Boolean) {
        enhanceButton.isEnabled = enabled
        uploadButton.isEnabled = enabled
        testAiButton.isEnabled = enabled

        saveButton.isEnabled =
            enabled && enhancedBitmap?.isRecycled == false

        compareButton.isEnabled =
            enabled && enhancedBitmap?.isRecycled == false

        strengthButtons.values.forEach {
            it.isEnabled = enabled
        }

        promptInput.isEnabled = enabled
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

    private fun readPhotoBytes(uri: Uri): ByteArray {
        val stream = contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot open selected photo")

        return stream.use {
            it.readBytes()
        }
    }

    private fun testCloudConnection() {
        if (isBusy()) return

        isTesting = true
        setControlsEnabled(false)
        statusMessageView.text = "Connecting to cloud AI server..."

        executor.execute {
            var message: String

            try {
                val connection = URL("$SERVER/health")
                    .openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 30000
                connection.readTimeout = 30000

                try {
                    val code = connection.responseCode

                    if (code in 200..299) {
                        message =
                            "CLOUD SERVER CONNECTED!\n" +
                            "Server: Online\n" +
                            "Health endpoint: Healthy\n" +
                            "Ready for photo enhancement."
                    } else {
                        message = "Server returned HTTP $code"
                    }
                } finally {
                    connection.disconnect()
                }

            } catch (e: Exception) {
                message = "Connection failed:\n${e.message}"
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                isTesting = false
                setControlsEnabled(true)
                statusMessageView.text = message

                Toast.makeText(
                    this,
                    if (message.startsWith("CLOUD SERVER CONNECTED"))
                        "Cloud server connected!"
                    else
                        "Cloud connection failed",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun enhancePhoto(uri: Uri) {
        if (isBusy()) return

        isProcessing = true

        val strength = selectedStrength
        val prompt = promptInput.text.toString().trim()

        if (prompt.length > 2000) {
            isProcessing = false
            Toast.makeText(
                this,
                "Instructions must be 2000 characters or less",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        setControlsEnabled(false)
        statusMessageView.text = "Preparing photo for cloud AI..."

        executor.execute {
            var result: Bitmap? = null

            try {
                val imageBytes = readPhotoBytes(uri)

                if (imageBytes.isEmpty()) {
                    throw Exception("Selected photo is empty")
                }

                if (imageBytes.size > MAX_UPLOAD_SIZE) {
                    throw Exception(
                        "Photo exceeds the 20 MB server upload limit"
                    )
                }

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        statusMessageView.text =
                            "Uploading photo to cloud AI..."
                    }
                }

                val boundary = "----PhotoEnhancer${UUID.randomUUID()}"

                val connection = URL("$SERVER/enhance")
                    .openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 60000
                connection.readTimeout = 240000
                connection.doOutput = true
                connection.useCaches = false

                connection.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=$boundary"
                )

                connection.setRequestProperty("Accept", "image/png")

                try {
                    connection.outputStream.use { output ->
                        fun writeText(value: String) {
                            output.write(value.toByteArray(Charsets.UTF_8))
                        }

                        writeText("--$boundary\r\n")
                        writeText(
                            "Content-Disposition: form-data; " +
                                "name=\"strength\"\r\n\r\n"
                        )
                        writeText("$strength\r\n")

                        writeText("--$boundary\r\n")
                        writeText(
                            "Content-Disposition: form-data; " +
                                "name=\"prompt\"\r\n\r\n"
                        )
                        writeText("$prompt\r\n")

                        writeText("--$boundary\r\n")
                        writeText(
                            "Content-Disposition: form-data; " +
                                "name=\"image\"; filename=\"photo.jpg\"\r\n"
                        )
                        writeText("Content-Type: image/jpeg\r\n\r\n")

                        output.write(imageBytes)
                        writeText("\r\n--$boundary--\r\n")
                        output.flush()
                    }

                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            statusMessageView.text =
                                "Cloud AI is enhancing your photo..."
                        }
                    }

                    val responseCode = connection.responseCode

                    if (responseCode !in 200..299) {
                        val errorText = try {
                            connection.errorStream
                                ?.bufferedReader()
                                ?.use { it.readText() }
                        } catch (_: Exception) {
                            null
                        }

                        throw Exception(
                            "Server error HTTP $responseCode: " +
                                (errorText ?: "Unknown server error")
                        )
                    }

                    val responseType = connection.contentType ?: ""

                    if (!responseType.contains("image/png", true)) {
                        throw Exception(
                            "Server did not return a PNG image"
                        )
                    }

                    val outputBytes = connection.inputStream.use {
                        it.readBytes()
                    }

                    result = BitmapFactory.decodeByteArray(
                        outputBytes,
                        0,
                        outputBytes.size
                    ) ?: throw Exception(
                        "Could not decode the enhanced image"
                    )

                } finally {
                    connection.disconnect()
                }

                val completed = result
                    ?: throw Exception("No enhanced image received")

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

                    statusMessageView.text =
                        "Cloud AI enhancement complete! ($strength)"

                    Toast.makeText(
                        this,
                        "Enhanced photo received from cloud AI!",
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

                    statusMessageView.text =
                        "Cloud enhancement failed:\n${e.message}"

                    Toast.makeText(
                        this,
                        "Cloud enhancement failed",
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

        if (isBusy()) return

        isSaving = true
        setControlsEnabled(false)
        statusMessageView.text = "Saving enhanced photo..."

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
                        statusMessageView.text =
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
                        statusMessageView.text =
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
                    statusMessageView.text = "Photo saved successfully!"

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
                    statusMessageView.text = "Save failed: ${e.message}"
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

            statusMessageView.text =
                "Original photo loaded. Ready for cloud AI enhancement!"

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
                    statusMessageView.text = "No save location selected"
                }
            } else {
                isSaving = false
                setControlsEnabled(true)
                statusMessageView.text = "Save cancelled"
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

                    val factor = newScale / currentScale

                    displayMatrix.postScale(
                        factor,
                        factor,
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
