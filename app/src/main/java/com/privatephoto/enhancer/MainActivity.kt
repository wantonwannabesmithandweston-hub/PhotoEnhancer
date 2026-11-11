

package com.privatephoto.enhancer

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var imageView: ImageView
    private lateinit var enhanceButton: Button
    private lateinit var saveButton: Button

    private var originalBitmap: Bitmap? = null
    private var enhancedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 32, 24, 24)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Private Photo Enhancer"
            textSize = 26f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Your photos stay private on your phone"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }

        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Selected photo preview"
            setBackgroundColor(Color.LTGRAY)
        }

        val chooseButton = Button(this).apply {
            text = "Select Photo"
            setOnClickListener {
                openPhotoPicker()
            }
        }

        val strength = TextView(this).apply {
            text = "Enhancement Strength: Balanced"
            textSize = 17f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }

        enhanceButton = Button(this).apply {
            text = "Enhance Photo"
            isEnabled = false
            setOnClickListener {
                enhancePhoto()
            }
        }

        saveButton = Button(this).apply {
            text = "Save Enhanced Photo"
            isEnabled = false
            setOnClickListener {
                savePhoto()
            }
        }

        layout.addView(title)
        layout.addView(subtitle)

        layout.addView(
            imageView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                300
            ).apply {
                setMargins(0, 30, 0, 20)
            }
        )

        layout.addView(chooseButton)
        layout.addView(strength)
        layout.addView(enhanceButton)
        layout.addView(saveButton)

        val scrollView = ScrollView(this).apply {
            addView(layout)
        }

        setContentView(scrollView)
    }

    private fun openPhotoPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }

        startActivityForResult(intent, 100)
    }

    private fun enhancePhoto() {
        val original = originalBitmap ?: return

        try {
            val output = Bitmap.createBitmap(
                original.width,
                original.height,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            val matrix = ColorMatrix().apply {
                setSaturation(1.08f)
            }

            val contrast = 1.12f
            val brightness = 4f
            val offset = (1f - contrast) * 128f + brightness

            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, offset,
                    0f, contrast, 0f, 0f, offset,
                    0f, 0f, contrast, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            matrix.postConcat(contrastMatrix)

            paint.colorFilter = ColorMatrixColorFilter(matrix)

            canvas.drawBitmap(original, 0f, 0f, paint)

            enhancedBitmap = output
            imageView.setImageBitmap(output)

            saveButton.isEnabled = true

            Toast.makeText(
                this,
                "Photo enhanced successfully!",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Enhancement failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun savePhoto() {
        if (enhancedBitmap == null) return

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/jpeg"
            putExtra(Intent.EXTRA_TITLE, "enhanced-photo.jpg")
        }

        startActivityForResult(intent, 101)
    }

    private fun loadPhoto(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }

            var sampleSize = 1

            while (
                bounds.outWidth / sampleSize > 2048 ||
                bounds.outHeight / sampleSize > 2048
            ) {
                sampleSize *= 2
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }

            val bitmap = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            if (bitmap != null) {
                originalBitmap = bitmap
                enhancedBitmap = null

                imageView.setImageBitmap(bitmap)
                enhanceButton.isEnabled = true
                saveButton.isEnabled = false

                Toast.makeText(
                    this,
                    "Photo loaded successfully!",
                    Toast.LENGTH_SHORT
                ).show()
            }

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Could not load photo",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK) return

        when (requestCode) {
            100 -> {
                data?.data?.let { loadPhoto(it) }
            }

            101 -> {
                val uri = data?.data
                val bitmap = enhancedBitmap

                if (uri != null && bitmap != null) {
                    try {
                        contentResolver.openOutputStream(uri)?.use {
                            bitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                95,
                                it
                            )
                        }

                        Toast.makeText(
                            this,
                            "Enhanced photo saved!",
                            Toast.LENGTH_LONG
                        ).show()

                    } catch (e: Exception) {
                        Toast.makeText(
                            this,
                            "Could not save photo",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
}
