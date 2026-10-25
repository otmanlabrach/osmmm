package com.otmanlabrach.osmsmartcoin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

class AdClickService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)
    private var currentState = State.IDLE
    private var lastActionTime = 0L
    private var waitUntil = 0L

    private lateinit var prefs: SharedPreferences

    enum class State {
        IDLE,
        LOOKING_FOR_AD_BUTTON,
        LOOKING_FOR_START_VIDEO,
        WAITING_FOR_AD_TO_FINISH,
        LOOKING_FOR_CLOSE_BUTTON,
        COOLDOWN
    }

    // كلمات مفتاحية شائعة في إعلانات OSM
    private val adButtonKeywords = listOf(
        "ad", "watch", "free", "coin", "coins", "boss", "reward",
        "إعلان", "شاهد", "مجاني", "كوين", "مكافأة"
    )

    private val startVideoKeywords = listOf(
        "start video", "start", "watch now", "watch ad", "play",
        "ابدأ", "شاهد الآن", "تشغيل"
    )

    private val closeKeywords = listOf(
        "close", "skip", "x", "×", "done", "continue", "reward",
        "إغلاق", "تخطي", "تم", "متابعة", "مكافأة"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("osm_smart_coin_prefs", MODE_PRIVATE)
        updateStatus("خدمة الوصول مفعّلة. اضغط بدء من التطبيق.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning.get()) return
        if (event == null) return

        val now = System.currentTimeMillis()
        if (now < waitUntil) return

        when (currentState) {
            State.LOOKING_FOR_AD_BUTTON -> tryClickAdButton()
            State.LOOKING_FOR_START_VIDEO -> tryClickStartVideo()
            State.WAITING_FOR_AD_TO_FINISH -> {
                // ننتظر الوقت المحدد ثم ننتقل لمرحلة البحث عن زر الإغلاق
                if (now >= waitUntil) {
                    currentState = State.LOOKING_FOR_CLOSE_BUTTON
                    updateStatus("انتهى وقت الانتظار، أبحث عن زر الإغلاق...")
                }
            }
            State.LOOKING_FOR_CLOSE_BUTTON -> tryClickCloseButton()
            State.COOLDOWN -> {
                if (now >= waitUntil) {
                    currentState = State.LOOKING_FOR_AD_BUTTON
                    updateStatus("جاهز للجولة التالية...")
                }
            }
            else -> {}
        }
    }

    override fun onInterrupt() {
        stopAutomation()
    }

    fun startAutomation() {
        if (isRunning.getAndSet(true)) return
        currentState = State.LOOKING_FOR_AD_BUTTON
        lastActionTime = 0L
        waitUntil = 0L
        updateStatus("بدأ التشغيل التلقائي... أبحث عن زر الإعلان")
        // نبدأ فحص فوري
        handler.post { tryClickAdButton() }
    }

    fun stopAutomation() {
        isRunning.set(false)
        currentState = State.IDLE
        updateStatus("تم إيقاف التشغيل التلقائي")
    }

    private fun tryClickAdButton() {
        val root = rootInActiveWindow ?: return
        val node = findClickableNode(root, adButtonKeywords)
        if (node != null) {
            performClick(node)
            currentState = State.LOOKING_FOR_START_VIDEO
            waitUntil = System.currentTimeMillis() + 1500
            updateStatus("تم الضغط على زر الإعلان، أنتظر ظهور Start video...")
        }
    }

    private fun tryClickStartVideo() {
        val root = rootInActiveWindow ?: return
        val node = findClickableNode(root, startVideoKeywords)
        if (node != null) {
            performClick(node)
            // ننتظر 25-30 ثانية تقريباً
            val waitSeconds = 28
            waitUntil = System.currentTimeMillis() + (waitSeconds * 1000L)
            currentState = State.WAITING_FOR_AD_TO_FINISH
            updateStatus("بدأ الإعلان... أنتظر $waitSeconds ثانية")
        }
    }

    private fun tryClickCloseButton() {
        val root = rootInActiveWindow ?: return

        // أولاً نبحث عن أزرار نصية
        var node = findClickableNode(root, closeKeywords)

        // إذا ما لقينا، نحاول نضغط في الزاوية العليا اليمنى (مكان الـ X الشائع)
        if (node == null) {
            val success = clickByCoordinates(0.93f, 0.08f) // نسبة من الشاشة
            if (success) {
                currentState = State.COOLDOWN
                waitUntil = System.currentTimeMillis() + 4000
                updateStatus("تم الضغط على منطقة الإغلاق، أنتظر إضافة الكوينز...")
                return
            }
        } else {
            performClick(node)
            currentState = State.COOLDOWN
            waitUntil = System.currentTimeMillis() + 4000
            updateStatus("تم إغلاق الإعلان، أنتظر إضافة الكوينز...")
        }
    }

    private fun findClickableNode(root: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
            val lower = text.lowercase()

            if (keywords.any { lower.contains(it.lowercase()) }) {
                if (node.isClickable) return node
                // جرب الأب إذا كان قابل للضغط
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) return parent
                    parent = parent.parent
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        // fallback: gesture click on center of the node
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        return clickByCoordinatesAbsolute(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    private fun clickByCoordinates(xPercent: Float, yPercent: Float): Boolean {
        val displayMetrics = resources.displayMetrics
        val x = displayMetrics.widthPixels * xPercent
        val y = displayMetrics.heightPixels * yPercent
        return clickByCoordinatesAbsolute(x, y)
    }

    private fun clickByCoordinatesAbsolute(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun updateStatus(message: String) {
        prefs.edit()
            .putString("last_state", message)
            .putBoolean("monitoring_active", isRunning.get())
            .apply()
    }

    companion object {
        @Volatile
        var instance: AdClickService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
