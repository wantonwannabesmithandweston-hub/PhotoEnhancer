
package com.privatephoto.enhancer

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var imageView: ImageView
    private lateinit var strengthText: TextView
    private lateinit var statusText: TextView
    private lateinit var enhanceButton: Button
    private lateinit var saveButton: Button
    private lateinit var uploadButton: Button

    private var selectedStrength = "Balanced"
    private var selectedImage: Uri? = null
    private var enhancedBitmap: Bitmap? = null

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
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.rgb(20, 20, 20))
        }

        val title = TextView(this).apply {
            text = "Private Photo Enhancer"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        layout.addView(title)

        imageView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(35, 35, 35))
        }

        layout.addView(
            imageView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(350)
            ).apply {
                setMargins(0, dp(20), 0, dp(20))
            }
        )

        uploadButton = Button(this).apply {
            text = "Upload Picture"
        }

        layout.addView(uploadButton)

        uploadButton.setOnClickListener {
            if (!isProcessing && !isSaving) {
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

    private fun enhancePhoto(uri: Uri) {

        if (isProcessing || isSaving) return

        val strength = selectedStrength

        isProcessing = true

        enhanceButton.isEnabled = false
        saveButton.isEnabled = false
        uploadButton.isEnabled = false

        enhancedBitmap?.let {
            imageView.setImageDrawable(null)
            if (!it.isRecycled) it.recycle()
        }

        enhancedBitmap = null

        statusText.text = "Processing your photo..."

        executor.execute {

            var original: Bitmap? = null
            var result: Bitmap? = null

            try {

                val bounds = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }

                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                } ?: throw Exception("Cannot open photo")

                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw Exception("Invalid or unsupported photo")
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

                original = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: throw Exception("Could not decode photo")

                val source = original
                    ?: throw Exception("Photo decoding failed")

                result = processImage(source, strength)

                original.recycle()
                original = null

                val completedBitmap = result
                    ?: throw Exception("Enhancement produced no image")

                runOnUiThread {

                    if (isFinishing || isDestroyed) {
                        completedBitmap.recycle()
                        result = null
                        return@runOnUiThread
                    }

                    enhancedBitmap = completedBitmap
                    result = null

                    imageView.setImageBitmap(completedBitmap)

                    statusText.text =
                        "Enhancement complete! ($strength)"

                    isProcessing = false

                    enhanceButton.isEnabled = true
                    uploadButton.isEnabled = true
                    saveButton.isEnabled = true

                    Toast.makeText(
                        this,
                        "Enhanced photo is ready! You can now save it.",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {

                original?.let {
                    if (!it.isRecycled) it.recycle()
                }

                result?.let {
                    if (!it.isRecycled) it.recycle()
                }

                runOnUiThread {

                    if (isFinishing || isDestroyed) return@runOnUiThread

                    isProcessing = false

                    statusText.text =
                        "Enhancement failed: ${e.message ?: "Unknown error"}"

                    enhanceButton.isEnabled = true
                    uploadButton.isEnabled = true
                    saveButton.isEnabled = false

                    Toast.makeText(
                        this,
                        "Enhancement failed: ${e.message}",
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

        if (isProcessing || isSaving) return

        isSaving = true

        saveButton.isEnabled = false
        enhanceButton.isEnabled = false
        uploadButton.isEnabled = false

        statusText.text = "Saving enhanced photo..."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            executor.execute {

                var savedUri: Uri? = null

                try {

                    val filename =
                        "Enhanced_${System.currentTimeMillis()}.jpg"

                    val values = ContentValues().apply {
                        put(
                            MediaStore.Images.Media.DISPLAY_NAME,
                            filename
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
                    ) ?: throw Exception("Could not open photo output")

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

                        statusText.text =
                            "Photo saved to Pictures/Private Photo Enhancer!"

                        saveButton.isEnabled = true
                        enhanceButton.isEnabled = true
                        uploadButton.isEnabled = true

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

                        statusText.text =
                            "Save failed: ${e.message}"

                        saveButton.isEnabled = true
                        enhanceButton.isEnabled = true
                        uploadButton.isEnabled = true

                        Toast.makeText(
                            this,
                            "Could not save photo: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

        } else {

            try {

                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/jpeg"
                    putExtra(
                        Intent.EXTRA_TITLE,
                        "Enhanced_Photo.jpg"
                    )
                }

                startActivityForResult(intent, SAVE_DOCUMENT)

            } catch (e: Exception) {

                isSaving = false
                saveButton.isEnabled = true
                enhanceButton.isEnabled = true
                uploadButton.isEnabled = true

                statusText.text = "Save failed: ${e.message}"
            }
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
                    uri,
                    "w"
                ) ?: throw Exception("Could not open photo output")

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

                    statusText.text = "Photo saved successfully!"

                    saveButton.isEnabled = true
                    enhanceButton.isEnabled = true
                    uploadButton.isEnabled = true

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

                    statusText.text =
                        "Save failed: ${e.message}"

                    saveButton.isEnabled = true
                    enhanceButton.isEnabled = true
                    uploadButton.isEnabled = true

                    Toast.makeText(
                        this,
                        "Could not save photo: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun processImage(
        source: Bitmap,
        strength: String
    ): Bitmap {

        val contrast = when (strength) {
            "Low" -> 1.08f
            "High" -> 1.25f
            else -> 1.15f
        }

        val sharpness = when (strength) {
            "Low" -> 0.20f
            "High" -> 0.70f
            else -> 0.40f
        }

        val brightness = when (strength) {
            "Low" -> 3f
            "High" -> 9f
            else -> 6f
        }

        val width = source.width
        val height = source.height

        val pixelCount = width * height

        val input = IntArray(pixelCount)
        val output = IntArray(pixelCount)

        source.getPixels(
            input,
            0,
            width,
            0,
            0,
            width,
            height
        )

        for (y in 0 until height) {

            for (x in 0 until width) {

                val index = y * width + x
                val center = input[index]

                val left = input[
                    y * width + maxOf(0, x - 1)
                ]

                val right = input[
                    y * width + minOf(width - 1, x + 1)
                ]

                val top = input[
                    maxOf(0, y - 1) * width + x
                ]

                val bottom = input[
                    minOf(height - 1, y + 1) * width + x
                ]

                fun enhance(channel: (Int) -> Int): Int {

                    val c = channel(center).toFloat()

                    val blur = (
                        channel(left) +
                        channel(right) +
                        channel(top) +
                        channel(bottom)
                    ) / 4f

                    val sharpened =
                        c + sharpness * (c - blur)

                    val adjusted =
                        (sharpened - 128f) * contrast +
                        128f + brightness

                    return adjusted.roundToInt()
                        .coerceIn(0, 255)
                }

                output[index] = Color.argb(
                    Color.alpha(center),
                    enhance { Color.red(it) },
                    enhance { Color.green(it) },
                    enhance { Color.blue(it) }
                )
            }
        }

        return Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        ).also {

            it.setPixels(
                output,
                0,
                width,
                0,
                0,
                width,
                height
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_IMAGE) {

            if (resultCode != RESULT_OK) return

            val uri = data?.data ?: return

            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Temporary access may still be available.
            }

            selectedImage = uri

            enhancedBitmap?.let {
                imageView.setImageDrawable(null)
                if (!it.isRecycled) it.recycle()
            }

            enhancedBitmap = null

            imageView.setImageURI(uri)

            saveButton.isEnabled = false

            statusText.text = "Original photo loaded"

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
                    statusText.text = "No save location selected"
                }

            } else {

                isSaving = false
                saveButton.isEnabled = true
                enhanceButton.isEnabled = true
                uploadButton.isEnabled = true

                statusText.text = "Save cancelled"
            }
        }
    }

    override fun onDestroy() {

        executor.shutdownNow()

        enhancedBitmap?.let {
            imageView.setImageDrawable(null)
            if (!it.isRecycled) it.recycle()
        }

        enhancedBitmap = null

        super.onDestroy()
    }
}
