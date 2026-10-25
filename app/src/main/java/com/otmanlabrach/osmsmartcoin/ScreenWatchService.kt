package com.otmanlabrach.osmsmartcoin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenWatchService : Service() {
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    @Volatile private var processingFrame = false
    @Volatile private var lastFrameAt = 0L
    @Volatile private var stopped = false
    private var lastPublishedState = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopObservation()
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val initial = buildNotification("جارٍ إعداد مراقبة الشاشة بموافقتك…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }

        if (projection != null) return START_NOT_STICKY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || data == null) {
            saveStatus("تعذّر بدء مشاركة الشاشة. أوقف المراقبة وأعد منح الإذن.", active = false)
            stopSelf()
            return START_NOT_STICKY
        }
        startObservation(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startObservation(resultCode: Int, data: Intent) {
        try {
            stopped = false
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels.coerceAtLeast(1)
            val height = displayMetrics.heightPixels.coerceAtLeast(1)
            val density = displayMetrics.densityDpi

            workerThread = HandlerThread("OSM-Screen-OCR").also { it.start() }
            workerHandler = Handler(workerThread!!.looper)
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    stopObservation()
                    stopSelf()
                }
            }
            projection?.registerCallback(projectionCallback!!, workerHandler)
            virtualDisplay = projection?.createVirtualDisplay(
                "OSM Smart Coin AI screen observer",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                workerHandler
            )
            imageReader?.setOnImageAvailableListener({ reader -> processLatestFrame(reader) }, workerHandler)
            saveStatus("المراقبة تعمل؛ افتح OSM وابدأ الخطوات يدويًا.", active = true)
            updateNotification("المراقبة تعمل — تحليل محلي للشاشة")
        } catch (e: Exception) {
            val errorMessage = "تعذّر تشغيل التعرّف على الشاشة: ${e.javaClass.simpleName}"
            stopObservation()
            saveStatus(errorMessage, active = false)
            stopSelf()
        }
    }

    private fun processLatestFrame(reader: ImageReader) {
        val now = SystemClock.elapsedRealtime()
        if (processingFrame || now - lastFrameAt < FRAME_INTERVAL_MS || stopped) {
            reader.acquireLatestImage()?.close()
            return
        }
        val image = reader.acquireLatestImage() ?: return
        lastFrameAt = now
        processingFrame = true
        var bitmap: Bitmap? = null
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            bitmap = if (paddedWidth == image.width) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        } catch (_: Exception) {
            // Some devices expose a screen buffer format that cannot be copied as RGBA.
        } finally {
            image.close()
        }
        val frame = bitmap
        if (frame == null) {
            processingFrame = false
            return
        }
        recognizer.process(InputImage.fromBitmap(frame, 0))
            .addOnSuccessListener { text ->
                val state = classifyScreen(text.text)
                if (state != null && state != lastPublishedState) {
                    lastPublishedState = state
                    saveStatus(state, active = true)
                    updateNotification(state)
                }
            }
            .addOnFailureListener {
                // Keep observing; never infer that a reward was earned from OCR failure.
            }
            .addOnCompleteListener {
                frame.recycle()
                processingFrame = false
            }
    }

    /** Classification only; does not tap, click, skip, or interact with ads. */
    private fun classifyScreen(rawText: String): String? {
        val text = rawText.lowercase()
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
        if (text.isBlank()) return null

        val hasBossCoins = text.contains("boss coins") || text.contains("boss coin")
        val hasStartVideo = text.contains("start video")
        val hasWatchAd = text.contains("watch an ad") || text.contains("watch ad") || text.contains("free boss coins")
        val hasAdSignals = text.contains("advertisement") || text.contains("sponsored") || text.contains("skip ad") || text.contains("ad choices") || text.contains("learn more") || text.contains("install now") || text.contains("play now") || text.contains("get app")
        val hasOsmSignals = text.contains("matchday") || text.contains("match preparation") || text.contains("quick match") || text.contains("online soccer manager")

        return when {
            hasStartVideo && (hasBossCoins || hasWatchAd) -> "تعرفت على نافذة تأكيد مكافأة OSM (Start video). القرار والنقر يبقيان بيدك."
            hasWatchAd && hasBossCoins -> "تعرفت على نص عرض Boss Coins؛ راجع الشاشة وتابع يدويًا."
            hasAdSignals -> "إعلان محتمل ظاهر. اتركه يكمل حسب تعليمات اللعبة، دون تفاعل آلي."
            hasOsmSignals -> "تعرفت على واجهة OSM أو شاشة المباراة. لم يتم تأكيد مكافأة."
            else -> "الشاشة الحالية غير معروفة؛ لم يتم تأكيد أنها إعلان أو أن المكافأة وصلت."
        }
    }

    private fun saveStatus(message: String, active: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_LAST_STATE, message)
            .putBoolean(PREF_ACTIVE, active)
            .apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "مراقبة شاشة OSM", NotificationManager.IMPORTANCE_LOW).apply {
                description = "حالة التعرّف المحلي على الشاشة"
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("OSM Smart Coin AI")
            .setContentText(message.take(180))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateNotification(message: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun stopObservation() {
        if (stopped) return
        stopped = true
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { projectionCallback?.let { callback -> projection?.unregisterCallback(callback) } } catch (_: Exception) {}
        projectionCallback = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { recognizer.close() } catch (_: Exception) {}
        try { workerThread?.quitSafely() } catch (_: Exception) {}
        workerThread = null
        workerHandler = null
        saveStatus("المراقبة متوقفة", active = false)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopObservation()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.otmanlabrach.osmsmartcoin.START_WATCH"
        const val ACTION_STOP = "com.otmanlabrach.osmsmartcoin.STOP_WATCH"
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        const val PREFS_NAME = "osm_smart_coin_prefs"
        const val PREF_LAST_STATE = "last_state"
        const val PREF_ACTIVE = "monitoring_active"
        private const val CHANNEL_ID = "osm_screen_watch"
        private const val NOTIFICATION_ID = 7781
        private const val FRAME_INTERVAL_MS = 3000L
    }
}
