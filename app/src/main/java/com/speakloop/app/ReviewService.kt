package com.speakloop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 复习播放引擎。
 *
 * 不用预先生成 mp3，而是让系统 TTS 边读边留白——好处是完全离线、不需要任何服务端，
 * 代价是不能导出成文件丢给播客 App。骑车听的时候前台服务保证息屏不断。
 */
class ReviewService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.speakloop.app.START"
        const val ACTION_STOP = "com.speakloop.app.STOP"
        const val ACTION_TOGGLE = "com.speakloop.app.TOGGLE"

        private const val CHANNEL_ID = "review"
        private const val NOTIF_ID = 42

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var paused: Boolean = false
            private set
    }

    /** 一句话 + 语速倍数 + 读完之后的留白毫秒数。 */
    data class Step(val text: String, val rate: Float, val pauseAfter: Long)

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingStart = false

    private val handler = Handler(Looper.getMainLooper())
    private var steps: List<Step> = emptyList()
    private var index = 0
    private var loop = true

    private val advanceTask = Runnable {
        index += 1
        playCurrent()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        val engine = tts ?: return
        if (!ready) {
            notifyState("语音引擎初始化失败")
            stopSelf()
            return
        }
        engine.language = Locale.US
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (!running || paused) return
                val gap = steps.getOrNull(index)?.pauseAfter ?: 800L
                handler.postDelayed(advanceTask, gap)
            }

            override fun onError(utteranceId: String?) {
                // 单句失败不该让整轮复习卡死，跳过继续
                if (!running || paused) return
                handler.postDelayed(advanceTask, 300L)
            }
        })
        if (pendingStart) {
            pendingStart = false
            playCurrent()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finish()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE -> {
                if (!running) return START_NOT_STICKY
                if (paused) {
                    paused = false
                    playCurrent()
                } else {
                    paused = true
                    handler.removeCallbacks(advanceTask)
                    tts?.stop()
                }
                updateNotification()
                return START_STICKY
            }

            else -> {
                val prefs = getSharedPreferences("speakloop", Context.MODE_PRIVATE)
                loop = prefs.getBoolean("loop", true)
                startReview()
                return START_STICKY
            }
        }
    }

    private fun startReview() {
        val db = Db(this)
        val phrases: List<Phrase>
        try {
            phrases = db.all()
            if (phrases.isEmpty()) {
                // 必须先把服务转成前台再退出：用 startForegroundService 拉起来的服务
                // 如果没调用过 startForeground 就 stopSelf，系统会判定为 RemoteServiceException。
                startForegroundCompat()
                notifyState("短语库是空的，先添加一条")
                finish()
                return
            }
            // 一整轮开始才记一次播放次数，避免循环时疯狂累加
            db.bumpPlay(phrases.map { p -> p.id })
        } finally {
            db.close()
        }
        steps = buildSteps(phrases)
        index = 0
        running = true
        paused = false

        startForegroundCompat()
        if (ready) playCurrent() else pendingStart = true
    }

    /**
     * 留白节奏：短语慢→等→短语正常→等→每句例句→长时间等（够跟读一遍）→对话→整轮结尾。
     * 这些数值和 Web 版保持一致，是实测过跟读能跟得上的间隔。
     */
    private fun buildSteps(phrases: List<Phrase>): List<Step> {
        val out = ArrayList<Step>()
        for (p in phrases) {
            out.add(Step(p.phrase, 0.75f, 800L))
            out.add(Step(p.phrase, 1.0f, 1200L))
            for (ex in p.examples) {
                out.add(Step(ex, 0.85f, 4000L))
            }
            for (line in p.dialogue) {
                val rate = if (line.speaker == "B") 0.9f else 0.95f
                out.add(Step(line.text, rate, 900L))
            }
            out.add(Step(p.phrase, 1.0f, 2500L))
        }
        return out
    }

    private fun playCurrent() {
        if (!running) return
        if (paused) return
        val engine = tts ?: return

        if (index >= steps.size) {
            if (loop) {
                index = 0
            } else {
                finish()
                return
            }
        }
        val step = steps[index]
        engine.setSpeechRate(step.rate)
        engine.speak(step.text, TextToSpeech.QUEUE_FLUSH, null, "s$index")
    }

    private fun finish() {
        running = false
        paused = false
        handler.removeCallbacks(advanceTask)
        tts?.stop()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        running = false
        paused = false
        super.onDestroy()
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID, "复习播放", NotificationManager.IMPORTANCE_LOW
            )
            channel.setSound(null, null)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String): Notification {
        val open = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val togglePending = PendingIntent.getService(
            this, 1, Intent(this, ReviewService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this, 2, Intent(this, ReviewService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("共 ${steps.size} 句，${if (loop) "循环播放" else "播完即停"}")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(running)
            .setContentIntent(openPending)
            .addAction(0, if (paused) "继续" else "暂停", togglePending)
            .addAction(0, "停止", stopPending)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("正在复习")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!running) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIF_ID, buildNotification(if (paused) "已暂停" else "正在复习"))
    }

    private fun notifyState(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        createChannel()
        manager.notify(NOTIF_ID + 1, buildNotification(text))
    }
}
