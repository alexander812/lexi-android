package dev.alexander812.lexi.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.alexander812.lexi.MainActivity
import kotlin.math.roundToInt

class DownloadService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            val active = activeDownload()

            if (active == null) {
                finish()
                return
            }

            notify(active.title, active.progress)
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = activeDownload()

        if (active == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(active.title, active.progress))
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, PROGRESS_INTERVAL_MS)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun finish() {
        handler.removeCallbacks(tick)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun activeDownload(): ActiveDownload? {
        val asr = AsrManager.get(this)

        ASR_ENTRIES.forEach { entry ->
            val state = asr.state(entry)

            if (state.downloading) return ActiveDownload("Загрузка: ${entry.title}", state.progress)
        }

        val voices = VoiceManager.get(this)

        VOICE_CATALOG.forEach { entry ->
            val state = voices.state(entry.lang)

            if (state.downloading) {
                return ActiveDownload("Загрузка голоса: ${entry.title}", state.progress)
            }
        }

        return null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Загрузки",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Загрузка моделей распознавания речи и голосов"
        }

        manager.createNotificationChannel(channel)
    }

    private fun notify(title: String, progress: Float) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, progress))
    }

    private fun buildNotification(title: String, progress: Float): Notification {
        val percent = (progress.coerceIn(0f, 1f) * 100).roundToInt()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("$percent% · загрузка продолжится при блокировке экрана")
            .setContentIntent(contentIntent)
            .setProgress(PERCENT_MAX, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private data class ActiveDownload(
        val title: String,
        val progress: Float,
    )

    companion object {
        private const val CHANNEL_ID = "model-download"
        private const val NOTIFICATION_ID = 41
        private const val PERCENT_MAX = 100
        private const val PROGRESS_INTERVAL_MS = 1000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DownloadService::class.java),
                )
            }
        }
    }
}
