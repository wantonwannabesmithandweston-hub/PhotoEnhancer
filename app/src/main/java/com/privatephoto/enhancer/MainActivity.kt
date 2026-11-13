
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

        // ONE LARGE PHOTO DISPLAY

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

        // ORIGINAL / ENHANCED SWITCH BUTTON

        compareButton = Button(this).apply {
            text = "View Original Photo"
            isEnabled = false
        }

        layout.addView(compareButton)

        compareButton.setOnClickListener {
            if (
                !isProcessing &&
                !isSaving &&
                enhancedBitmap != null
            ) {
                isShowingEnhanced = !isShowingEnhanced
                displayCurrentPhoto()
            }
        }

        uploadButton = Button(this).apply {
            text = "Upload Picture"
        }
        layout.addView(uploadButton)

        uploadButton.setOnClickListener {
            if (!isProcessing && !isSaving) {
                val intent = Intent(
                    Intent.ACTION_OPEN_DOCUMENT
                ).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    addFlags(
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
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
                if (!isProcessing && !isSaving) {
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

            } else if (!isProcessing && !isSaving) {
                enhancePhoto(uri)
            }
        }

        saveButton.setOnClickListener {
            if (!isProcessing && !isSaving) {
                saveEnhancedPhoto()
            }
        }

        scroll.addView(layout)
        setContentView(scroll)

        updateStrengthButtons()

        statusText.text = "Upload a photo to get started"
    }

    // DISPLAY ORIGINAL OR ENHANCED PHOTO

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

        // Always fit the newly displayed image
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

        return stream.use { it.readBytes() }
    }

    // PHOTO ENHANCEMENT

    private fun enhancePhoto(uri: Uri) {

        if (isProcessing || isSaving) return

        isProcessing = true

        val strength = selectedStrength

        enhanceButton.isEnabled = false
        saveButton.isEnabled = false
        uploadButton.isEnabled = false
        compareButton.isEnabled = false

        statusText.text = "Enhancing photo..."

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

                if (
                    bounds.outWidth <= 0 ||
                    bounds.outHeight <= 0
                ) {
                    throw Exception(
                        "Unsupported or invalid image"
                    )
                }

                var sample = 1

                while (
                    bounds.outWidth / sample > 1600 ||
                    bounds.outHeight / sample > 1600
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
                    result = processImage(source, strength)
                } finally {
                    source.recycle()
                }

                val completed = result
                    ?: throw Exception(
                        "No enhanced image produced"
                    )

                runOnUiThread {

                    if (isFinishing || isDestroyed) {
                        completed.recycle()
                        return@runOnUiThread
                    }

                    enhancedBitmap?.let {
                        if (!it.isRecycled) it.recycle()
                    }

                    enhancedBitmap = completed

                    // Automatically show enhanced photo
                    isShowingEnhanced = true
                    displayCurrentPhoto()

                    compareButton.isEnabled = true

                    statusText.text =
                        "Enhancement complete! ($strength)"

                    isProcessing = false

                    enhanceButton.isEnabled = true
                    uploadButton.isEnabled = true
                    saveButton.isEnabled = true

                    Toast.makeText(
                        this,
                        "Enhanced photo is ready!",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                result = null

            } catch (e: Exception) {

                result?.let {
                    if (!it.isRecycled) it.recycle()
                }

                runOnUiThread {

                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }

                    isProcessing = false

                    statusText.text =
                        "Enhancement failed: ${e.message}"

                    enhanceButton.isEnabled = true
                    uploadButton.isEnabled = true
                    saveButton.isEnabled =
                        enhancedBitmap != null

                    compareButton.isEnabled =
                        enhancedBitmap != null
                }
            }
        }
    }

    // IMAGE PROCESSING ENGINE

    private fun processImage(
        source: Bitmap,
        strength: String
    ): Bitmap {

        val contrast = when (strength) {
            "Low" -> 1.10f
            "High" -> 1.30f
            else -> 1.20f
        }

        val sharpness = when (strength) {
            "Low" -> 0.55f
            "High" -> 1.20f
            else -> 0.85f
        }

        val brightness = when (strength) {
            "Low" -> 2f
            "High" -> 7f
            else -> 4f
        }

        val saturation = when (strength) {
            "Low" -> 1.04f
            "High" -> 1.15f
            else -> 1.09f
        }

        val width = source.width
        val height = source.height

        val input = IntArray(width * height)
        val output = IntArray(width * height)

        source.getPixels(
            input, 0, width, 0, 0, width, height
        )

        fun channel(pixel: Int, shift: Int): Int =
            (pixel shr shift) and 255

        for (y in 0 until height) {

            for (x in 0 until width) {

                val index = y * width + x
                val center = input[index]

                val r = channel(center, 16).toFloat()
                val g = channel(center, 8).toFloat()
                val b = channel(center, 0).toFloat()

                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var count = 0

                for (dy in -1..1) {

                    for (dx in -1..1) {

                        if (dx == 0 && dy == 0) continue

                        val nx = (x + dx)
                            .coerceIn(0, width - 1)

                        val ny = (y + dy)
                            .coerceIn(0, height - 1)

                        val neighbor = input[ny * width + nx]

                        sumR += channel(neighbor, 16)
                        sumG += channel(neighbor, 8)
                        sumB += channel(neighbor, 0)

                        count++
                    }
                }

                val blurR = sumR / count
                val blurG = sumG / count
                val blurB = sumB / count

                fun adjust(
                    value: Float,
                    blur: Float
                ): Float {

                    val sharpened =
                        value + sharpness * (value - blur)

                    return (
                        (sharpened - 128f) * contrast +
                            128f + brightness
                    ).coerceIn(0f, 255f)
                }

                var nr = adjust(r, blurR)
                var ng = adjust(g, blurG)
                var nb = adjust(b, blurB)

                val gray =
                    0.299f * nr +
                    0.587f * ng +
                    0.114f * nb

                nr = (
                    gray + (nr - gray) * saturation
                ).coerceIn(0f, 255f)

                ng = (
                    gray + (ng - gray) * saturation
                ).coerceIn(0f, 255f)

                nb = (
                    gray + (nb - gray) * saturation
                ).coerceIn(0f, 255f)

                output[index] = Color.argb(
                    Color.alpha(center),
                    nr.roundToInt(),
                    ng.roundToInt(),
                    nb.roundToInt()
                )
            }
        }

        return Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        ).also {
            it.setPixels(
                output, 0, width, 0, 0, width, height
            )
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

        if (isProcessing || isSaving) return

        isSaving = true

        saveButton.isEnabled = false
        enhanceButton.isEnabled = false
        uploadButton.isEnabled = false
        compareButton.isEnabled = false

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
                    ) ?: throw Exception(
                        "Could not create photo"
                    )

                    val output = contentResolver.openOutputStream(
                        savedUri, "w"
                    ) ?: throw Exception(
                        "Could not open output"
                    )

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
                        savedUri, finished, null, null
                    )

                    runOnUiThread {

                        if (isFinishing || isDestroyed) {
                            return@runOnUiThread
                        }

                        isSaving = false

                        statusText.text =
                            "Photo saved to your gallery!"

                        saveButton.isEnabled = true
                        enhanceButton.isEnabled = true
                        uploadButton.isEnabled = true
                        compareButton.isEnabled = true

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

                        if (isFinishing || isDestroyed) {
                            return@runOnUiThread
                        }

                        isSaving = false

                        statusText.text =
                            "Save failed: ${e.message}"

                        saveButton.isEnabled = true
                        enhanceButton.isEnabled = true
                        uploadButton.isEnabled = true
                        compareButton.isEnabled = true
                    }
                }
            }

        } else {

            val intent = Intent(
                Intent.ACTION_CREATE_DOCUMENT
            ).apply {

                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/jpeg"

                putExtra(
                    Intent.EXTRA_TITLE,
                    "Enhanced_Photo.jpg"
                )
            }

            startActivityForResult(
                intent,
                SAVE_DOCUMENT
            )
        }
    }

    private fun saveBitmapToUri(uri: Uri) {

        val bitmap = enhancedBitmap

        if (bitmap == null || bitmap.isRecycled) {

            isSaving = false
            saveButton.isEnabled = false
            enhanceButton.isEnabled = true
            uploadButton.isEnabled = true

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

                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }

                    isSaving = false

                    statusText.text =
                        "Photo saved successfully!"

                    saveButton.isEnabled = true
                    enhanceButton.isEnabled = true
                    uploadButton.isEnabled = true
                    compareButton.isEnabled = true

                    Toast.makeText(
                        this,
                        "Enhanced photo saved!",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {

                runOnUiThread {

                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }

                    isSaving = false

                    statusText.text =
                        "Save failed: ${e.message}"

                    saveButton.isEnabled = true
                    enhanceButton.isEnabled = true
                    uploadButton.isEnabled = true
                    compareButton.isEnabled = true
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

        if (
            requestCode == PICK_IMAGE &&
            resultCode == RESULT_OK
        ) {

            val uri = data?.data ?: return

            try {

                val flags =
                    data.flags and
                        Intent.FLAG_GRANT_READ_URI_PERMISSION

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
                "Original photo loaded. Ready to enhance!"

            return
        }

        if (requestCode == SAVE_DOCUMENT) {

            if (resultCode == RESULT_OK) {

                val uri = data?.data

                if (uri != null) {

                    saveBitmapToUri(uri)

                } else {

                    isSaving = false
                    saveButton.isEnabled = true
                    enhanceButton.isEnabled = true
                    uploadButton.isEnabled = true
                    compareButton.isEnabled = true

                    statusText.text =
                        "No save location selected"
                }

            } else {

                isSaving = false

                saveButton.isEnabled = true
                enhanceButton.isEnabled = true
                uploadButton.isEnabled = true
                compareButton.isEnabled =
                    enhancedBitmap != null

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

    private val imageMatrixValues = FloatArray(9)
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

                    parent?.requestDisallowInterceptTouchEvent(
                        true
                    )

                    return true
                }

                override fun onScale(
                    detector: ScaleGestureDetector
                ): Boolean {

                    val factor = detector.scaleFactor

                    val newScale =
                        (currentScale * factor)
                            .coerceIn(1f, maximumZoom)

                    val actualFactor =
                        newScale / currentScale

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

                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {

                    if (currentScale > 1.1f) {

                        resetZoom()

                    } else {

                        val factor = 2.5f

                        displayMatrix.postScale(
                            factor,
                            factor,
                            e.x,
                            e.y
                        )

                        currentScale = factor

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

        val imageWidth =
            drawable.intrinsicWidth.toFloat()

        val imageHeight =
            drawable.intrinsicHeight.toFloat()

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

        val left =
            (width - imageWidth * fitScale) / 2f

        val top =
            (height - imageHeight * fitScale) / 2f

        displayMatrix.reset()

        displayMatrix.setScale(
            fitScale,
            fitScale
        )

        displayMatrix.postTranslate(
            left,
            top
        )

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

            dx =
                (width - rect.width()) / 2f - rect.left

        } else if (rect.left > 0f) {

            dx = -rect.left

        } else if (rect.right < width) {

            dx = width - rect.right
        }

        if (rect.height() <= height) {

            dy =
                (height - rect.height()) / 2f - rect.top

        } else if (rect.top > 0f) {

            dy = -rect.top

        } else if (rect.bottom < height) {

            dy = height - rect.bottom
        }

        displayMatrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

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

                parent?.requestDisallowInterceptTouchEvent(
                    true
                )
            }

            MotionEvent.ACTION_MOVE -> {

                if (
                    !scaleDetector.isInProgress &&
                    !scaling
                ) {

                    if (currentScale > 1.01f) {

                        val dx = event.x - lastX
                        val dy = event.y - lastY

                        displayMatrix.postTranslate(
                            dx,
                            dy
                        )

                        limitMovement()

                        imageMatrix = displayMatrix

                        parent?.requestDisallowInterceptTouchEvent(
                            true
                        )
                    }
                }

                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {

                scaling = false

                parent?.requestDisallowInterceptTouchEvent(
                    false
                )
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

        super.onSizeChanged(
            w,
            h,
            oldw,
            oldh
        )

        fitImage()
    }
}
