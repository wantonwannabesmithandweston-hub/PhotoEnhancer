 
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(enhanceButton)
        layout.addView(settings)

        setContentView(layout)
    }
}
