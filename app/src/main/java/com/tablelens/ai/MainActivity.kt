package com.tablelens.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Setup screen: save the Claude API key, then START puts the floating SCAN button over the game.
 * Large text and one button per step, no clutter.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var keyField: EditText
    private lateinit var status: TextView

    private val captureConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, ScanService::class.java)
                    .putExtra(ScanService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ScanService.EXTRA_RESULT_DATA, result.data)
                startForegroundService(intent)
                status.text = "Running. Open your blackjack game and tap the green SCAN button."
                moveTaskToBack(true)
            } else {
                status.text = "Screen sharing was not allowed. Tap START and choose \"Entire screen\"."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply {
            text = "🃏 TableLens AI"
            textSize = 30f
        })
        layout.addView(TextView(this).apply {
            text = "Tap SCAN over your game and Claude reads the table and tells you the move."
            textSize = 18f
            setPadding(0, 16, 0, 32)
        })

        layout.addView(TextView(this).apply {
            text = "1. Claude API key"
            textSize = 20f
        })
        keyField = EditText(this).apply {
            hint = "sk-ant-..."
            textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(loadKey(this@MainActivity))
        }
        layout.addView(keyField)
        layout.addView(Button(this).apply {
            text = "SAVE KEY"
            textSize = 18f
            setOnClickListener {
                saveKey(keyField.text.toString().trim())
                Toast.makeText(this@MainActivity, "Key saved", Toast.LENGTH_SHORT).show()
            }
        })

        layout.addView(TextView(this).apply {
            text = "\n2. Start"
            textSize = 20f
        })
        layout.addView(Button(this).apply {
            text = "START TABLELENS"
            textSize = 20f
            setOnClickListener { start() }
        })
        layout.addView(Button(this).apply {
            text = "STOP"
            textSize = 18f
            setOnClickListener {
                startService(Intent(this@MainActivity, ScanService::class.java).setAction(ScanService.ACTION_STOP))
                status.text = "Stopped."
            }
        })

        status = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.START
            setPadding(0, 32, 0, 0)
        }
        layout.addView(status)

        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && status.text.isNullOrEmpty()) {
            status.text = if (loadKey(this).isEmpty()) "Paste your API key, tap SAVE KEY, then START." else "Ready. Tap START."
        }
    }

    private fun start() {
        val typed = keyField.text.toString().trim()
        if (typed.isNotEmpty() && typed != loadKey(this)) saveKey(typed)
        if (loadKey(this).isEmpty()) {
            status.text = "Paste your Claude API key first, then tap SAVE KEY."
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Turn on \"Allow display over other apps\" for TableLens AI, then come back and tap START again."
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureConsent.launch(mpm.createScreenCaptureIntent())
    }

    private fun saveKey(key: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_API, key).apply()
    }

    companion object {
        private const val PREFS = "tablelens_ai"
        private const val KEY_API = "api_key"

        fun loadKey(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API, "") ?: ""
    }
}
