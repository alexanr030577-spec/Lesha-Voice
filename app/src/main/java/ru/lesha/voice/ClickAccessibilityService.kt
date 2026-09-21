package ru.lesha.voice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class ClickAccessibilityService : AccessibilityService() {
    private val clickHandler = Handler(Looper.getMainLooper())
    private val gestureHandler = Handler(Looper.getMainLooper())
    private var generation = 0L
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private var marker: View? = null
    private var pickerBar: View? = null
    private val loop = ClickLoop(object : ClickLoop.Scheduler {
        override fun nowMs() = SystemClock.uptimeMillis()
        override fun postDelayed(delayMs: Long, action: () -> Unit) {
            clickHandler.postDelayed(action, delayMs)
        }
        override fun clear() { clickHandler.removeCallbacksAndMessages(null) }
    }, ::tap)

    override fun onServiceConnected() {
        instance = this
        VoiceClickService.stop("Нажатия разрешены. Выберите точку и нажмите СТАРТ.")
    }

    fun hasValidPoint(): Boolean {
        val prefs = getSharedPreferences("clicker", MODE_PRIVATE)
        val screen = screenMetrics()
        return prefs.contains("x") && prefs.getInt("width", -1) == screen.widthPixels &&
            prefs.getInt("height", -1) == screen.heightPixels &&
            prefs.getInt("rotation", -1) == windows.defaultDisplay.rotation &&
            prefs.getInt("x", -1) in 0 until screen.widthPixels &&
            prefs.getInt("y", -1) in 0 until screen.heightPixels
    }

    fun startClicks(initialDelayMs: Long) {
        if (!hasValidPoint()) {
            VoiceClickService.stop("Экран изменился. Выберите точку заново.")
            return
        }
        val prefs = getSharedPreferences("clicker", MODE_PRIVATE)
        generation++
        loop.start(prefs.getInt("x", 0).toFloat(), prefs.getInt("y", 0).toFloat(),
            prefs.getLong("interval", 1000).coerceIn(100L, 60_000L), initialDelayMs)
    }

    fun pauseClicks() = loop.pause()
    fun resumeClicks() = loop.resume()

    fun stopClicks() {
        // Invalidate callbacks before cancelling work. No callback may restart a stopped run.
        generation++
        loop.stop()
        gestureHandler.removeCallbacksAndMessages(null)
    }

    private fun tap(x: Float, y: Float, completed: (Boolean) -> Unit) {
        if (!SessionState.active || !hasValidPoint()) {
            VoiceClickService.stop("Клики остановлены: проверьте выбранную точку")
            completed(false)
            return
        }
        val expectedGeneration = generation
        var delivered = false
        val timeout = Runnable {
            if (generation == expectedGeneration && !delivered) {
                delivered = true
                VoiceClickService.stop("Нажатие не подтверждено системой. Клики остановлены.")
                completed(false)
            }
        }
        fun finish(success: Boolean) {
            if (generation != expectedGeneration || delivered) return
            delivered = true
            gestureHandler.removeCallbacks(timeout)
            if (!success) VoiceClickService.stop("Нажатие прервано. Клики остановлены.")
            completed(success)
        }
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 1)
        ).build()
        gestureHandler.postDelayed(timeout, 2000)
        try {
            if (!dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) = finish(true)
                override fun onCancelled(gestureDescription: GestureDescription) = finish(false)
            }, gestureHandler)) finish(false)
        } catch (_: RuntimeException) {
            finish(false)
        }
    }

    fun showPointPicker() {
        if (SessionState.active) return
        hidePointPicker()
        val size = dp(56)
        val metrics = screenMetrics()
        val params = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (metrics.widthPixels - size) / 2
            y = (metrics.heightPixels - size) / 2
        }
        val target = TextView(this).apply {
            text = "+"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            contentDescription = "Перетащите центр прицела в точку нажатия"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xDD80CBC4.toInt())
                setStroke(dp(2), Color.BLACK)
            }
        }
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        target.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    originX = params.x; originY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val current = screenMetrics()
                    params.x = (originX + event.rawX - downX).toInt().coerceIn(0, (current.widthPixels - size).coerceAtLeast(0))
                    params.y = (originY + event.rawY - downY).toInt().coerceIn(0, (current.heightPixels - size).coerceAtLeast(0))
                    windows.updateViewLayout(target, params)
                    true
                }
                MotionEvent.ACTION_UP -> { view.performClick(); true }
                else -> true
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.WHITE)
            addView(TextView(this@ClickAccessibilityService).apply {
                text = "Откройте нужный экран,\nперетащите прицел и сохраните точку"
                setTextColor(Color.BLACK)
                textSize = 16f
            })
            addView(LinearLayout(this@ClickAccessibilityService).apply {
                addView(Button(this@ClickAccessibilityService).apply {
                    text = "Сохранить"
                    setOnClickListener {
                        val location = IntArray(2)
                        target.getLocationOnScreen(location)
                        val screen = screenMetrics()
                        getSharedPreferences("clicker", MODE_PRIVATE).edit()
                            .putInt("x", location[0] + target.width / 2)
                            .putInt("y", location[1] + target.height / 2)
                            .putInt("width", screen.widthPixels).putInt("height", screen.heightPixels)
                            .putInt("rotation", windows.defaultDisplay.rotation).apply()
                        hidePointPicker()
                        SessionState.update(false, "Точка сохранена. Вернитесь в Лёша и нажмите СТАРТ.")
                        Toast.makeText(this@ClickAccessibilityService, SessionState.message, Toast.LENGTH_LONG).show()
                    }
                })
                addView(Button(this@ClickAccessibilityService).apply {
                    text = "Отмена"
                    setOnClickListener { hidePointPicker() }
                })
            })
        }
        marker = target
        pickerBar = bar
        try {
            windows.addView(target, params)
            windows.addView(bar, overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(48)
            })
        } catch (_: RuntimeException) {
            hidePointPicker()
            SessionState.update(false, "Не удалось показать прицел. Перезапустите разрешение нажатий.")
        }
    }

    fun hidePointPicker() {
        listOfNotNull(marker, pickerBar).forEach { if (it.isAttachedToWindow) windows.removeView(it) }
        marker = null
        pickerBar = null
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private fun screenMetrics() = DisplayMetrics().also { windows.defaultDisplay.getRealMetrics(it) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { VoiceClickService.stop("Сервис нажатий прерван") }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hidePointPicker()
        if (SessionState.active) VoiceClickService.stop("Экран изменился. Выберите точку заново.")
    }

    override fun onDestroy() {
        VoiceClickService.stop("Сервис нажатий отключён")
        stopClicks()
        hidePointPicker()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        var instance: ClickAccessibilityService? = null
            private set
    }
}
