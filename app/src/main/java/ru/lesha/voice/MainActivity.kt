package ru.lesha.voice

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var point: TextView
    private lateinit var interval: EditText
    private lateinit var access: Button
    private lateinit var select: Button
    private lateinit var start: Button
    private val stateChanged: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(box) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        fun label(text: String, size: Float = 18f) = TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, dp(8), 0, dp(8))
            box.addView(this)
        }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            minHeight = dp(56)
            setOnClickListener { action() }
            box.addView(this)
        }
        label("Лёша · кликер", 28f)
        label("Кликает в выбранной точке. Скажите «Лёша» — клики остановятся. Голос может быть любым.")
        status = label(SessionState.message, 22f)
        access = button("1. Разрешить нажатия") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        label("В специальных возможностях включите «Лёша · нажатия». Сервис выполняет нажатия в выбранной вами точке и не читает содержимое экрана.", 16f)
        point = label("")
        select = button("2. Выбрать точку") {
            val service = ClickAccessibilityService.instance
            if (service == null) {
                status.text = "Сначала разрешите нажатия в специальных возможностях"
            } else {
                service.showPointPicker()
            }
        }
        label("Интервал между нажатиями, мс (100–60 000)")
        interval = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(getSharedPreferences("clicker", MODE_PRIVATE).getLong("interval", 1000).toString())
            contentDescription = "Интервал между нажатиями в миллисекундах"
            box.addView(this)
        }
        start = button("3. СТАРТ") { requestStart() }
        button("STOP") { VoiceClickService.stop("Остановлено кнопкой STOP") }
        label("После старта есть 3 секунды, чтобы открыть нужный экран. STOP также доступен в уведомлении. Если прослушивание прервётся, клики приостановятся.", 16f)
        label("Нужен системный сервис распознавания русской речи; ему может потребоваться интернет. После распознавания слова новые клики сразу отменяются.", 16f)
        setContentView(scroll)
    }

    override fun onStart() {
        super.onStart()
        SessionState.listeners.add(stateChanged)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        SessionState.listeners.remove(stateChanged)
        super.onStop()
    }

    private fun refresh() {
        status.text = SessionState.message
        val prefs = getSharedPreferences("clicker", MODE_PRIVATE)
        point.text = if (prefs.contains("x")) "Точка: ${prefs.getInt("x", 0)}, ${prefs.getInt("y", 0)}"
            else "Точка пока не выбрана"
        access.text = if (ClickAccessibilityService.instance != null) "Нажатия разрешены ✓"
            else "1. Разрешить нажатия"
        start.isEnabled = !SessionState.active
        select.isEnabled = !SessionState.active
        interval.isEnabled = !SessionState.active
        access.isEnabled = !SessionState.active
    }

    private fun requestStart() {
        if (SessionState.active) return
        val service = ClickAccessibilityService.instance
        if (service == null) {
            status.text = "Сначала разрешите нажатия"
            return
        }
        if (!service.hasValidPoint()) {
            status.text = "Выберите точку для текущего положения экрана"
            return
        }
        val delay = interval.text.toString().toLongOrNull()
        if (delay == null || delay !in 100L..60_000L) {
            interval.error = "Введите число от 100 до 60 000"
            return
        }
        getSharedPreferences("clicker", MODE_PRIVATE).edit().putLong("interval", delay).apply()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (!getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false) ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            } else {
                openNotificationSettings()
            }
            return
        }
        val notifications = getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled() ||
            notifications.getNotificationChannel("voice_stop")?.importance == NotificationManager.IMPORTANCE_NONE) {
            openNotificationSettings()
            return
        }
        service.hidePointPicker()
        try {
            VoiceClickService.start(this)
        } catch (_: RuntimeException) {
            SessionState.update(false, "Не удалось запустить микрофон. Откройте приложение и попробуйте снова.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        when (requestCode) {
            MICROPHONE_REQUEST -> if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestStart()
                else status.text = "Для голосового STOP разрешите микрофон"
            NOTIFICATION_REQUEST -> if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestStart()
                else status.text = "Разрешите уведомления, чтобы кнопка STOP была доступна поверх работы в других приложениях"
        }
    }

    private fun openNotificationSettings() {
        status.text = "Разрешите уведомления для кнопки STOP, затем нажмите СТАРТ"
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MICROPHONE_REQUEST = 7
        private const val NOTIFICATION_REQUEST = 8
    }
}
