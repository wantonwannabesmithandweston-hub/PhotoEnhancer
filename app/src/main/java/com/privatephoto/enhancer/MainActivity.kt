
package com.privatephoto.enhancer

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
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

    private var selectedStrength = "Balanced"
    private var selectedImage: Uri? = null

    private val strengthButtons = mutableMapOf<String, Button>()
    private val executor = Executors.newSingleThreadExecutor()

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
                dp(320)
            ).apply {
                setMargins(0, dp(20), 0, dp(20))
            }
        )

        val uploadButton = Button(this).apply {
            text = "Upload Picture"
        }

        layout.addView(uploadButton)

        uploadButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
            }
            startActivityForResult(intent, 100)
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
                selectedStrength = strength
                updateStrengthButtons()
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

        enhanceButton.setOnClickListener {
            val uri = selectedImage

            if (uri == null) {
                Toast.makeText(
                    this,
                    "Please upload a photo first",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                enhancePhoto(uri)
            }
        }

        scroll.addView(layout)
        setContentView(scroll)

        updateStrengthButtons()
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

        val strength = selectedStrength

        enhanceButton.isEnabled = false
        statusText.text = "Processing your photo..."

        executor.execute {

            var original: Bitmap? = null

            try {

                val bounds = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }

                val boundsStream = contentResolver.openInputStream(uri)
    ?: throw Exception("Cannot open photo")

boundsStream.use {
    BitmapFactory.decodeStream(it, null, bounds)
}

                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw Exception("Invalid photo")
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

                val enhanced = processImage(original!!, strength)

                original?.recycle()
                original = null

                runOnUiThread {

                    imageView.setImageBitmap(enhanced)
                    imageView.invalidate()

                    statusText.text =
                        "Enhancement complete! ($strength)"

                    enhanceButton.isEnabled = true

                    Toast.makeText(
                        this,
                        "Your enhanced photo is ready!",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {

                original?.recycle()

                runOnUiThread {

                    statusText.text =
                        "Enhancement failed: ${e.message}"

                    enhanceButton.isEnabled = true

                    Toast.makeText(
                        this,
                        "Enhancement failed",
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

        val input = IntArray(width * height)
        val output = IntArray(width * height)

        source.getPixels(
            input, 0, width, 0, 0, width, height
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

    @Deprecated("Deprecated in Android")
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

        if (requestCode == 100 && resultCode == RESULT_OK) {

            selectedImage = data?.data

            if (selectedImage != null) {

                imageView.setImageURI(selectedImage)

                statusText.text = "Original photo loaded"
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
