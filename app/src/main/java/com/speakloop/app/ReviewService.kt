package com.speakloop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * 复习播放引擎。
 *
 * 不用预先生成 mp3，而是让系统 TTS 边读边留白——好处是完全离线、不需要任何服务端，
 * 代价是不能导出成文件丢给播客 App。骑车听的时候前台服务保证息屏不断。
 *
 * 一轮的范围由启动时传进来的参数决定（默认「今天新加的 + 从没练过的」），
 * 播完就停、不自动循环：一次通勤听一轮，听了几遍自己心里有数。
 *
 * 留白不再是 handler.postDelayed 一整段黑盒，而是拆成 100ms 一跳，
 * 这样界面才能画那个 4→3→2→1 的倒数圆环。
 */
class ReviewService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.speakloop.app.START"
        const val ACTION_STOP = "com.speakloop.app.STOP"
        const val ACTION_TOGGLE = "com.speakloop.app.TOGGLE"
        const val ACTION_NEXT = "com.speakloop.app.NEXT"

        /** yyyy-MM-dd，不传就是今天 */
        const val EXTRA_DAY = "day"
        /** 是否补上「从没练过的」那些旧词 */
        const val EXTRA_INCLUDE_DUE = "includeDue"
        /** 这一轮最多几条短语，0 = 不限 */
        const val EXTRA_LIMIT = "limit"
        /** 只读短语本身，跳过例句和对话 */
        const val EXTRA_PHRASES_ONLY = "phrasesOnly"

        private const val CHANNEL_ID = "review"
        private const val NOTIF_ID = 42

        /** 留白倒数的步长，100ms 一跳够画顺滑的圆环，也不至于太费电 */
        private const val TICK_MS = 100L

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var paused: Boolean = false
            private set

        /** 复习页是常驻 Activity，用 startService 发指令比 bind 简单得多 */
        fun start(c: Context, intent: Intent) {
            intent.setClass(c, ReviewService::class.java)
            intent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(intent)
            } else {
                c.startService(intent)
            }
        }

        fun command(c: Context, action: String) {
            val i = Intent(c, ReviewService::class.java).setAction(action)
            try {
                c.startService(i)
            } catch (ignored: Exception) {
                // 服务已经不在时不要再炸一次
            }
        }
    }

    /**
     * 一句要读的话。
     *
     * [phraseIndex] 和 [phraseId] 是为了两件事：界面显示「第 3 条」，
     * 以及读到某条的第一个字时给它记账（而不是开播就把全表 +1）。
     */
    private data class Step(
        val text: String,
        val cn: String,
        val rate: Float,
        val pauseAfter: Long,
        val phraseIndex: Int,
        val phraseId: Long,
        val phraseTitle: String
    )

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingStart = false

    private val handler = Handler(Looper.getMainLooper())
    private var steps: List<Step> = emptyList()
    private var index = 0
    private var loop = false

    /** 本轮短语总数，用来显示「第 3 / 8 条」 */
    private var phraseTotal = 0
    private var scopeLabel = ""

    /** 已经记过账的短语，避免同一条读第二遍时重复 +1 */
    private val marked = HashSet<Long>()

    // 留白倒数
    private var inGap = false
    private var gapTotal = 0L
    private var gapRemain = 0L

    private var session: MediaSession? = null

    /** 显式标类型：这个 Runnable 会 postDelayed 自己，不写类型编译器会卡在递归推断上。 */
    private val tick: Runnable = Runnable {
        if (!running || paused || !inGap) return@Runnable
        gapRemain -= TICK_MS
        if (gapRemain <= 0L) {
            inGap = false
            gapRemain = 0L
            publishGap()
            advance()
        } else {
            publishGap()
            handler.postDelayed(tick, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        createSession()
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
                beginGap(gap)
            }

            override fun onError(utteranceId: String?) {
                // 单句失败不该让整轮复习卡死，跳过继续
                if (!running || paused) return
                beginGap(300L)
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
                if (paused) resume() else pause()
                updateNotification()
                return START_STICKY
            }

            ACTION_NEXT -> {
                if (!running) return START_NOT_STICKY
                skipNext()
                return START_STICKY
            }

            else -> {
                startReview(intent)
                return START_STICKY
            }
        }
    }

    // ---------- 组队 ----------

    private fun startReview(intent: Intent?) {
        val prefs = getSharedPreferences("speakloop", Context.MODE_PRIVATE)
        loop = prefs.getBoolean("loop", false)
        val followGap = prefs.getBoolean("followGap", true)
        val longGap = if (followGap) 4000L else 500L
        val rateScale = prefs.getFloat("rate", 1f)

        val dayText = intent?.getStringExtra(EXTRA_DAY).orEmpty()
        val day = try {
            if (dayText.isBlank()) LocalDate.now() else LocalDate.parse(dayText)
        } catch (e: Exception) {
            LocalDate.now()
        }
        val includeDue = intent?.getBooleanExtra(EXTRA_INCLUDE_DUE, true)
            ?: prefs.getBoolean("includeDue", true)
        val limit = intent?.getIntExtra(EXTRA_LIMIT, prefs.getInt("queueLimit", 15))
            ?: prefs.getInt("queueLimit", 15)
        val phrasesOnly = intent?.getBooleanExtra(EXTRA_PHRASES_ONLY, false) ?: false

        val db = Db(this)
        val queue: List<Phrase>
        try {
            val zone = ZoneId.systemDefault()
            val startMs = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMs = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val added = db.addedOn(startMs, endMs)
            val addedIds = added.map { it.id }.toSet()
            val due = if (includeDue) db.neverPlayed().filter { it.id !in addedIds } else emptyList()

            val merged = ArrayList<Phrase>(added)
            merged.addAll(due)
            queue = if (limit > 0) merged.take(limit) else merged

            scopeLabel = buildString {
                append(if (day == LocalDate.now()) "今日" else "${day.monthValue}月${day.dayOfMonth}日")
                append(' ').append(added.size)
                if (due.isNotEmpty()) {
                    append(" + 待复习 ").append(minOf(due.size, maxOf(0, queue.size - added.size)))
                }
                append(" 条")
            }

            if (queue.isEmpty()) {
                // 必须先把服务转成前台再退出：用 startForegroundService 拉起来的服务
                // 没调用过 startForeground 就 stopSelf，系统会判定为 RemoteServiceException。
                startForegroundCompat()
                notifyState("这一天没有可练的短语")
                finish()
                return
            }
        } finally {
            db.close()
        }

        phraseTotal = queue.size
        steps = buildSteps(queue, longGap, phrasesOnly, rateScale)
        index = 0
        marked.clear()
        running = true
        paused = false

        startForegroundCompat()
        if (ready) playCurrent() else pendingStart = true
    }

    /**
     * 留白节奏：短语慢→等→短语正常→长留白（够跟读一遍）→每句例句→短留白→对话→收尾。
     * 数值沿用 Web 版实测过的间隔；「留白关掉」时长留白压到 0.5 秒，一轮能快一倍。
     */
    private fun buildSteps(
        phrases: List<Phrase>,
        longGap: Long,
        phrasesOnly: Boolean,
        rateScale: Float
    ): List<Step> {
        val out = ArrayList<Step>()
        // 每个基准倍速都乘上设置里的全局语速：基准值之间的相对快慢保持不变
        fun k(r: Float) = (r * rateScale).coerceIn(0.3f, 2.0f)
        phrases.forEachIndexed { i, p ->
            out.add(Step(p.phrase, p.meaning, k(0.75f), 800L, i, p.id, p.phrase))
            out.add(Step(p.phrase, p.meaning, k(1.0f), longGap, i, p.id, p.phrase))
            if (phrasesOnly) return@forEachIndexed
            for (ex in p.examples) {
                out.add(Step(ex.text, ex.cn, k(0.85f), longGap, i, p.id, p.phrase))
            }
            for (line in p.dialogue) {
                val rate = if (line.speaker == "B") 0.9f else 0.95f
                out.add(Step(line.text, line.cn, k(rate), 900L, i, p.id, p.phrase))
            }
            out.add(Step(p.phrase, p.meaning, k(1.0f), 1200L, i, p.id, p.phrase))
        }
        return out
    }

    // ---------- 播放 ----------

    private fun playCurrent() {
        if (!running) return
        if (paused) return
        val engine = tts ?: return

        if (index >= steps.size) {
            if (loop) {
                index = 0
                marked.clear()
            } else {
                finish()
                return
            }
        }
        val step = steps[index]

        // 记账放在「真的开始读这条」的瞬间
        if (marked.add(step.phraseId)) {
            val db = Db(this)
            try {
                db.markPlayed(step.phraseId, LocalDate.now().toString())
            } finally {
                db.close()
            }
        }

        inGap = false
        gapRemain = 0
        engine.setSpeechRate(step.rate)
        engine.speak(step.text, TextToSpeech.QUEUE_FLUSH, null, "s$index")
        publish(false)
        updateNotification()
    }

    private fun beginGap(ms: Long) {
        inGap = true
        gapTotal = ms
        gapRemain = ms
        publish(true)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    private fun advance() {
        index += 1
        playCurrent()
    }

    private fun pause() {
        if (!running || paused) return
        paused = true
        handler.removeCallbacks(tick)
        if (!inGap) tts?.stop()
        publish(inGap)
        updateSessionState()
    }

    private fun resume() {
        if (!running || !paused) return
        paused = false
        if (inGap) {
            handler.postDelayed(tick, TICK_MS)
            publish(true)
        } else {
            playCurrent()
        }
        updateSessionState()
    }

    private fun skipNext() {
        if (!running) return
        handler.removeCallbacks(tick)
        inGap = false
        tts?.stop()
        advance()
    }

    private fun finish() {
        running = false
        paused = false
        inGap = false
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        session?.isActive = false
        ReviewBus.reset()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        session?.release()
        session = null
        running = false
        paused = false
        super.onDestroy()
    }

    // ---------- 对外发布 ----------

    private fun publish(gap: Boolean) {
        val step = steps.getOrNull(index)
        ReviewBus.publish(
            ReviewBus.State(
                running = running,
                paused = paused,
                text = step?.text.orEmpty(),
                cn = step?.cn.orEmpty(),
                gap = gap,
                remainMs = if (gap) gapRemain else 0L,
                gapTotalMs = if (gap) gapTotal else 0L,
                phraseIndex = (step?.phraseIndex ?: 0) + 1,
                phraseTotal = phraseTotal,
                phrase = step?.phraseTitle.orEmpty(),
                scopeLabel = scopeLabel,
                stepIndex = index + 1,
                stepTotal = steps.size
            )
        )
    }

    /** 倒数只在留白时刷新，不动别的字段，省得每 100ms 重建整个状态。 */
    private fun publishGap() {
        val step = steps.getOrNull(index)
        ReviewBus.publish(
            ReviewBus.state.copy(
                text = step?.text.orEmpty(),
                cn = step?.cn.orEmpty(),
                gap = true,
                remainMs = gapRemain,
                gapTotalMs = gapTotal,
                phraseIndex = (step?.phraseIndex ?: 0) + 1,
                phraseTotal = phraseTotal,
                phrase = step?.phraseTitle.orEmpty(),
                stepIndex = index + 1,
                stepTotal = steps.size
            )
        )
    }

    // ---------- 耳机 / 蓝牙按键 ----------

    /**
     * 用系统自带的 MediaSession，不引 androidx.media。
     * 骑车时手不空，线控按一下暂停、双击下一句，比掏手机安全得多。
     */
    private fun createSession() {
        session = MediaSession(this, "SpeakLoop").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    resume()
                    updateNotification()
                }

                override fun onPause() {
                    pause()
                    updateNotification()
                }

                override fun onSkipToNext() = skipNext()
                override fun onStop() = finish()
            })
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(
                        PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        1f
                    )
                    .setActions(
                        PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_STOP
                    )
                    .build()
            )
        }
    }

    private fun updateSessionState() {
        val s = session ?: return
        val state = when {
            !running -> PlaybackState.STATE_STOPPED
            paused -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_PLAYING
        }
        s.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActions(
                    PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_STOP
                )
                .build()
        )
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
        val nextPending = PendingIntent.getService(
            this, 2, Intent(this, ReviewService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this, 3, Intent(this, ReviewService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val step = steps.getOrNull(index)
        val sub = if (step != null) {
            "第 ${step.phraseIndex + 1} / $phraseTotal 条 · ${scopeLabel}"
        } else {
            scopeLabel
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(sub)
            .setSubText(if (paused) "已暂停" else null)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(running)
            .setContentIntent(openPending)
            .addAction(0, if (paused) "继续" else "暂停", togglePending)
            .addAction(0, "下一句", nextPending)
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
        updateSessionState()
    }

    private fun notifyState(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        createChannel()
        manager.notify(NOTIF_ID + 1, buildNotification(text))
    }
}
