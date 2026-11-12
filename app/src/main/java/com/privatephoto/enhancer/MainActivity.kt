

package com.privatephoto.enhancer

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var imageView: ImageView
    private lateinit var strengthText: TextView

    private var selectedStrength = "Balanced"
    private var selectedImage: Uri? = null

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
            LinearLayout.LayoutParams.MATCH_PARENT,
            350
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

        val strengthLabel = TextView(this)
        strengthLabel.text = "Enhancement Strength"
        strengthLabel.textSize = 20f
        strengthLabel.setTextColor(Color.WHITE)
        strengthLabel.gravity = Gravity.CENTER
        layout.addView(strengthLabel)

        strengthText = TextView(this)
        strengthText.text = "Selected: Balanced"
        strengthText.textSize = 16f
        strengthText.setTextColor(Color.GREEN)
        strengthText.gravity = Gravity.CENTER
        layout.addView(strengthText)

        val strengths = listOf("Low", "Balanced", "High")

        for (strength in strengths) {
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
            if (selectedImage == null) {
                Toast.makeText(
                    this,
                    "Please upload a photo first",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Ready for $selectedStrength enhancement. AI connection is next!",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        setContentView(layout)
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
}
