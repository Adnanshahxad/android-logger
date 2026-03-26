package com.logger.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.logger.R
import com.logger.data.SettingsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DecoyActivity : AppCompatActivity() {

    private var clickCount = 0
    private var lastClickTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If decoy screen is disabled, skip straight to main
        if (!SettingsManager(this).isDecoyEnabled) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_decoy)

        supportActionBar?.hide()

        val trigger = findViewById<TextView>(R.id.secretTrigger)
        trigger.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastClickTime > 1000) {
                clickCount = 0
            }
            lastClickTime = now
            clickCount++

            if (clickCount >= 3) {
                clickCount = 0
                showPasswordDialog()
            }
        }
    }

    private fun showPasswordDialog() {
        val input = EditText(this).apply {
            hint = "Enter code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Verification")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val todayCode = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                if (input.text.toString() == todayCode) {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
