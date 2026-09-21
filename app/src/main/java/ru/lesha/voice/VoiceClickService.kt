package ru.lesha.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VoiceClickService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var clicksStarted = false
    private var recognitionSession = 0L
    private var startDeadline = 0L
    private var failuresBeforeReady = 0
    private val watchdog = Runnable { stopRun("Распознавание не отвечает. Клики остановлены.") }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) stopRun("Экран выключен. Клики остановлены.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop("Остановлено кнопкой STOP")
            return START_NOT_STICKY
        }
        try {
            startForeground(1, notification())
        } catch (_: RuntimeException) {
            stopRun("Нет доступа к микрофону. Разрешите его и запустите приложение снова.")
            return START_NOT_STICKY
        }
        // STOP also invalidates starts still queued in Android's service manager.
        if (intent == null || intent.getLongExtra(EXTRA_REQUEST, -1) != requestedSession) {
            if (!running) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val clicker = ClickAccessibilityService.instance
        if (clicker == null || !clicker.hasValidPoint()) {
            stopRun("Разрешите нажатия и выберите точку")
            return START_NOT_STICKY
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopRun("Распознавание речи недоступно. Включите системный сервис русской речи.")
            return START_NOT_STICKY
        }
        running = true
        clicksStarted = false
        failuresBeforeReady = 0
        startDeadline = SystemClock.uptimeMillis() + 3000
        SessionState.update(true, "Запускаю микрофон. Откройте нужный экран — старт через 3 секунды.")
        listen()
        return START_NOT_STICKY
    }

    private fun listen() {
        if (!running) return
        val token = ++recognitionSession
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 8000)
        try {
            val speech = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer = speech
            speech.setRecognitionListener(object : RecognitionListener {
                private fun current() = running && token == recognitionSession

                override fun onReadyForSpeech(params: Bundle?) {
                    if (!current()) return
                    failuresBeforeReady = 0
                    handler.removeCallbacks(watchdog)
                    // Bound a provider that reports ready and then stops responding.
                    handler.postDelayed(watchdog, 30_000)
                    val clicker = ClickAccessibilityService.instance
                    if (clicker == null) {
                        stopRun("Сервис нажатий отключён")
                        return
                    }
                    if (!clicksStarted) {
                        clicksStarted = true
                        clicker.startClicks((startDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
                    } else {
                        clicker.resumeClicks()
                    }
                    if (running) SessionState.update(true, "Слушаю. Скажите «Лёша», чтобы остановить клики.")
                }

                override fun onPartialResults(results: Bundle?) {
                    if (current()) checkStop(results)
                }

                override fun onResults(results: Bundle?) {
                    if (!current()) return
                    if (!checkStop(results)) restartListening()
                }

                override fun onEndOfSpeech() {
                    if (!current()) return
                    ClickAccessibilityService.instance?.pauseClicks()
                    handler.removeCallbacks(watchdog)
                    handler.postDelayed(watchdog, 10_000)
                    SessionState.update(true, "Распознаю речь. Клики на паузе.")
                }

                override fun onError(error: Int) {
                    if (!current()) return
                    ClickAccessibilityService.instance?.pauseClicks()
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        failuresBeforeReady++
                        if (failuresBeforeReady < 3) restartListening()
                        else stopRun("Микрофон не готов. Клики остановлены.")
                    } else {
                        stopRun("Ошибка распознавания ($error). Клики остановлены; проверьте микрофон и связь.")
                    }
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            })
        } catch (_: RuntimeException) {
            stopRun("Не удалось включить распознавание. Клики остановлены.")
        }
    }

    private fun checkStop(results: Bundle?): Boolean {
        if (!VoiceStopCommand.matches(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))) return false
        // Stop the click queue synchronously, before any recognizer or notification cleanup.
        stopRun("STOP — услышал «Лёша». Клики остановлены.")
        return true
    }

    private fun restartListening() {
        if (!running) return
        ClickAccessibilityService.instance?.pauseClicks()
        recognitionSession++
        handler.removeCallbacksAndMessages(null)
        releaseRecognizer()
        SessionState.update(true, "Возобновляю прослушивание. Клики на паузе.")
        handler.postDelayed({ if (running) listen() }, 200)
    }

    private fun stopRun(message: String) {
        ClickAccessibilityService.instance?.stopClicks()
        running = false
        clicksStarted = false
        recognitionSession++
        requestedSession++
        handler.removeCallbacksAndMessages(null)
        SessionState.update(false, message)
        releaseRecognizer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseRecognizer() {
        val old = recognizer
        recognizer = null
        try { old?.cancel() } catch (_: RuntimeException) { }
        try { old?.destroy() } catch (_: RuntimeException) { }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Голосовой STOP", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, VoiceClickService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Лёша · кликер")
            .setContentText("Скажите «Лёша» или нажмите STOP")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "STOP", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { stopRun("Приложение закрыто. Клики остановлены.") }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ClickAccessibilityService.instance?.stopClicks()
        running = false
        recognitionSession++
        handler.removeCallbacksAndMessages(null)
        releaseRecognizer()
        unregisterReceiver(screenOff)
        if (SessionState.active) SessionState.update(false, "Сервис остановлен. Клики остановлены.")
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "voice_stop"
        private const val ACTION_STOP = "ru.lesha.voice.STOP"
        private const val EXTRA_REQUEST = "request"
        private var requestedSession = 0L
        private var instance: VoiceClickService? = null

        fun start(context: Context) {
            val request = ++requestedSession
            SessionState.update(true, "Запускаю микрофон…")
            context.startForegroundService(Intent(context, VoiceClickService::class.java).putExtra(EXTRA_REQUEST, request))
        }

        fun stop(message: String) {
            // All entry points (recognition, buttons, lifecycle) run on the main thread.
            requestedSession++
            ClickAccessibilityService.instance?.stopClicks()
            val service = instance
            if (service != null) service.stopRun(message)
            else SessionState.update(false, message)
        }
    }
}
