package com.tablelens.ai

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Holds the screen-capture session and the floating SCAN panel.
 * Each tap: hide the panel, grab one frame, show "Reading…", ask Claude, show the move.
 * Nothing is captured or sent until SCAN is tapped.
 */
class ScanService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var densityDpi = 0

    private lateinit var windowManager: WindowManager
    private var panel: LinearLayout? = null
    private lateinit var resultView: TextView
    private lateinit var scanButton: Button
    private var scanning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be in the foreground with the mediaProjection type before using the consent token.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (projection == null && data != null && resultCode == Activity.RESULT_OK) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, data)?.also { p ->
                // Android 14 requires a callback before creating the virtual display.
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        main.post { stopSelf() }
                    }
                }, main)
                startCapture(p)
            }
            showPanel()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removePanel()
        virtualDisplay?.release()
        reader?.close()
        projection?.stop()
        projection = null
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- Screen capture ----

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    @SuppressLint("WrongConstant")
    private fun startCapture(p: MediaProjection) {
        val (w, h) = screenSize()
        captureWidth = w
        captureHeight = h
        densityDpi = resources.displayMetrics.densityDpi
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = p.createVirtualDisplay(
            "TableLensAI", w, h, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, main
        )
    }

    /** Phone turned sideways (or back) since START: resize the capture to match. */
    @SuppressLint("WrongConstant")
    private fun matchRotation() {
        val (w, h) = screenSize()
        if (w == captureWidth && h == captureHeight) return
        val old = reader
        val fresh = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay?.resize(w, h, densityDpi)
        virtualDisplay?.surface = fresh.surface
        reader = fresh
        captureWidth = w
        captureHeight = h
        old?.close()
    }

    private fun grabFrame(): Bitmap? {
        val image = reader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            val exact = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            if (exact != padded) padded.recycle()
            exact
        } finally {
            image.close()
        }
    }

    // ---- Scan flow ----

    private fun scan() {
        if (scanning) return
        if (reader == null) {
            show("ERROR", "Screen capture isn't running. Open TableLens AI and tap START.")
            return
        }
        scanning = true
        scanButton.isEnabled = false
        matchRotation()
        // Hide the panel so it isn't in the picture, then wait for a fresh frame.
        panel?.visibility = View.INVISIBLE
        main.postDelayed({ tryGrab(attempt = 0) }, 350)
    }

    private fun tryGrab(attempt: Int) {
        val frame = grabFrame()
        if (frame == null && attempt < 6) {
            main.postDelayed({ tryGrab(attempt + 1) }, 150)
            return
        }
        panel?.visibility = View.VISIBLE
        if (frame == null) {
            finishScan("ERROR", "Couldn't get a picture of the screen. Tap SCAN again.")
            return
        }
        show("…", "Reading the table…")
        val key = MainActivity.loadKey(this)
        worker.execute {
            val advice = try {
                ClaudeAdvisor(key).advise(toJpegBase64(frame))
            } catch (e: Exception) {
                Advice("ERROR", "", "", "No connection to Claude. Check internet and try again.", "low")
            } finally {
                frame.recycle()
            }
            main.post { finishScan(advice) }
        }
    }

    private fun toJpegBase64(frame: Bitmap): String {
        // Claude reads images up to ~1568 px on the long side; larger only costs more.
        val scale = 1568f / max(frame.width, frame.height)
        val sized = if (scale < 1f) {
            Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt(), (frame.height * scale).roundToInt(), true)
        } else frame
        val out = ByteArrayOutputStream()
        sized.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (sized != frame) sized.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun finishScan(advice: Advice) {
        val details = buildString {
            if (advice.playerHand.isNotEmpty()) append("You: ${advice.playerHand}\n")
            if (advice.dealerCard.isNotEmpty()) append("Dealer: ${advice.dealerCard}\n")
            append(advice.reason)
            if (advice.action != "ERROR" && advice.action != "NONE") append("\n(${advice.confidence} confidence)")
        }
        finishScan(advice.action, details)
    }

    private fun finishScan(action: String, details: String) {
        show(action, details)
        scanning = false
        scanButton.isEnabled = true
    }

    // ---- Floating panel ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel() {
        if (panel != null) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(235, 18, 18, 18))
            }
        }
        val handle = TextView(this).apply {
            text = "⠿ TableLens AI   ✕"
            textSize = 13f
            setTextColor(Color.LTGRAY)
        }
        resultView = TextView(this).apply {
            text = "Tap SCAN on your turn"
            textSize = 16f
            setTextColor(Color.WHITE)
            maxWidth = dp(240)
            setPadding(0, dp(6), 0, dp(8))
        }
        scanButton = Button(this).apply {
            text = "SCAN"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.rgb(11, 140, 80))
            }
            setOnClickListener { scan() }
        }
        box.addView(handle)
        box.addView(resultView)
        box.addView(scanButton, LinearLayout.LayoutParams(dp(240), dp(52)))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(80)
        }

        // Drag by the title bar; tap the ✕ end of it to stop.
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        handle.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > dp(4) || kotlin.math.abs(dy) > dp(4)) moved = true
                    params.x = startX + dx; params.y = startY + dy
                    windowManager.updateViewLayout(box, params)
                }
                MotionEvent.ACTION_UP -> if (!moved && e.x > v.width * 0.8f) stopSelf()
            }
            true
        }

        windowManager.addView(box, params)
        panel = box
    }

    private fun removePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
    }

    private fun show(action: String, details: String) {
        val color = when (action) {
            "HIT" -> Color.rgb(255, 183, 77)
            "STAND" -> Color.rgb(129, 199, 132)
            "DOUBLE" -> Color.rgb(100, 181, 246)
            "SPLIT" -> Color.rgb(206, 147, 216)
            "ERROR" -> Color.rgb(239, 83, 80)
            else -> Color.WHITE
        }
        val headline = when (action) {
            "…", "ERROR" -> ""
            "NONE" -> "No move yet\n"
            else -> "$action\n"
        }
        resultView.text = headline + details
        resultView.setTextColor(color)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "TableLens AI", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("TableLens AI is ready")
            .setContentText("Only captures the screen when you tap SCAN")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.tablelens.ai.STOP"
        private const val CHANNEL = "tablelens_ai"
        private const val NOTIFICATION_ID = 7
    }
}
