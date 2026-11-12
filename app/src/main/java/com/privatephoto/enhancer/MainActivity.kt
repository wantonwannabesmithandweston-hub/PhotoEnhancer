
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
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var imageView: ImageView
    private lateinit var strengthText: TextView

    private var selectedStrength = "Balanced"
    private var selectedImage: Uri? = null

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.gravity = Gravity.CENTER
        layout.setPadding(30, 30, 30, 30)
        layout.setBackgroundColor(Color.rgb(20, 20, 20))

        val title = TextView(this)
        title.text = "Private Photo Enhancer"
        title.textSize = 26f
        title.setTextColor(Color.WHITE)
        title.gravity = Gravity.CENTER
        layout.addView(title)

        imageView = ImageView(this)
        imageView.adjustViewBounds = true
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER

        val imageParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 350
        )
        imageParams.setMargins(0, 25, 0, 25)
        layout.addView(imageView, imageParams)

        val uploadButton = Button(this)
        uploadButton.text = "Upload Picture"
        layout.addView(uploadButton)

        uploadButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            startActivityForResult(intent, 100)
        }

        val label = TextView(this)
        label.text = "Enhancement Strength"
        label.textSize = 20f
        label.setTextColor(Color.WHITE)
        label.gravity = Gravity.CENTER
        layout.addView(label)

        strengthText = TextView(this)
        strengthText.text = "Selected: Balanced"
        strengthText.textSize = 16f
        strengthText.setTextColor(Color.GREEN)
        strengthText.gravity = Gravity.CENTER
        layout.addView(strengthText)

        for (strength in listOf("Low", "Balanced", "High")) {
            val button = Button(this)
            button.text = strength
            layout.addView(button)

            button.setOnClickListener {
                selectedStrength = strength
                strengthText.text = "Selected: $strength"
            }
        }

        val enhanceButton = Button(this)
        enhanceButton.text = "Enhance Photo"
        layout.addView(enhanceButton)

        enhanceButton.setOnClickListener {
            val uri = selectedImage

            if (uri == null) {
                Toast.makeText(
                    this, "Please upload a photo first",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                enhancePhoto(uri)
            }
        }

        setContentView(layout)
    }

    private fun enhancePhoto(uri: Uri) {
        val strength = selectedStrength

        Toast.makeText(
            this, "Enhancing photo...",
            Toast.LENGTH_SHORT
        ).show()

        executor.execute {
            try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }

                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }

                var sample = 1
                while (
                    options.outWidth / sample > 1600 ||
                    options.outHeight / sample > 1600
                ) {
                    sample *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sample
                }

                val original =
                    contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(
                            it, null, decodeOptions
                        )
                    } ?: throw Exception("Cannot open photo")

                val enhanced = processImage(original, strength)

                runOnUiThread {
                    imageView.setImageBitmap(enhanced)

                    Toast.makeText(
                        this,
                        "Enhancement complete!",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Enhancement failed: ${e.message}",
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
            "Low" -> 0.15f
            "High" -> 0.55f
            else -> 0.30f
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

                val left = input[y * width + maxOf(0, x - 1)]
                val right = input[y * width + minOf(width - 1, x + 1)]
                val top = input[maxOf(0, y - 1) * width + x]
                val bottom = input[minOf(height - 1, y + 1) * width + x]

                fun enhance(channel: (Int) -> Int): Int {
                    val c = channel(center).toFloat()

                    val blur = (
                        channel(left) +
                        channel(right) +
                        channel(top) +
                        channel(bottom)
                    ) / 4f

                    val sharpened = c + sharpness * (c - blur)

                    val adjusted =
                        (sharpened - 128f) * contrast + 128f

                    return adjusted.roundToInt().coerceIn(0, 255)
                }

                output[index] = Color.rgb(
                    enhance { Color.red(it) },
                    enhance { Color.green(it) },
                    enhance { Color.blue(it) }
                )
            }
        }

        return Bitmap.createBitmap(
            width, height, Bitmap.Config.ARGB_8888
        ).also {
            it.setPixels(output, 0, width, 0, 0, width, height)
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 100 && resultCode == RESULT_OK) {
            selectedImage = data?.data
            imageView.setImageURI(selectedImage)
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
