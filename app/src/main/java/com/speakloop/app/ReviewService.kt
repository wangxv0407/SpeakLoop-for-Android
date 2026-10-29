package com.speakloop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
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
        const val ACTION_PREV = "com.speakloop.app.PREV"

        /**
         * 跟读结束，立刻往下走。
         *
         * 跟读留白给到 8 秒是为了说完一句话，但用户点「停下，看结果」之后
         * 没必要再干等剩下几秒 —— 骑车时每一秒都在等红灯。
         */
        const val ACTION_GAP_DONE = "com.speakloop.app.GAP_DONE"

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

        /**
         * 系统媒体面板按这组 actions 决定显示哪些按钮 —— Android 13+ 的通知栏播放控件、
         * 锁屏、蓝牙 AVRCP 全都只认这里，addAction 完全不参与。
         * 少声明一个，对应按钮就不出现：加「上一条」时忘了这里，通知栏就只剩播放暂停。
         */
        private val SESSION_ACTIONS: Long =
            PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_STOP

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
        val phraseTitle: String,
        /** 行首小标签：短语 / 例句 / A / B */
        val tag: String = "短语"
    )

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingStart = false
    private var engineAttached = false

    private val handler = Handler(Looper.getMainLooper())
    private var steps: List<Step> = emptyList()
    private var index = 0
    private var loop = false

    /** 本轮短语总数，用来显示「第 3 / 8 条」 */
    private var phraseTotal = 0
    private var scopeLabel = ""

    /** 已经记过账的短语，避免同一条读第二遍时重复 +1 */
    private val marked = HashSet<Long>()

    /**
     * 当前这条短语要读的所有行 + 正读到第几行。
     * 复习页整块列出来并高亮当前行，所以播放时算一次缓存，倒数 tick 复用，不重复构造。
     */
    private var currentLines: List<ReviewBus.Line> = emptyList()
    private var currentCursor = 0
    private var cachedPhraseIndex = -1

    // 留白倒数
    private var inGap = false
    private var gapTotal = 0L
    private var gapRemain = 0L

    /** 这一轮留白的绝对结束时刻（elapsedRealtime）。倒数按它算，才不会因为 tick 被拖延而漂移。 */
    private var gapDeadline = 0L

    private var session: MediaSession? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /**
     * 留白结束。推进只由这个任务负责，**不挂在倒数 tick 上**。
     *
     * 之前 tick 既画倒数又负责 advance，一旦某次 tick 没被调度（低电、被系统挂起、
     * 或者链路上任何一次异常），整轮复习就永远停在那一句上——表现就是
     * 「读完一遍短语就没声了」。现在推进只排一次 postDelayed，倒数另有任务。
     */
    private val gapDone: Runnable = Runnable {
        if (!running || paused || !inGap) return@Runnable
        inGap = false
        gapRemain = 0L
        publishGap()
        advance()
    }

    /** 倒数显示。只更新剩余毫秒，并在到点时兜底推进一步。 */
    private val tick: Runnable = Runnable {
        if (!running || paused || !inGap) return@Runnable
        val remain = (gapDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        gapRemain = remain
        publishGap()
        if (remain <= 0L) {
            // gapDone 万一没被执行（消息被清、被系统吞），这里也推进一次；
            // 两者都用 inGap 防重入，不会走两步。
            gapDone.run()
            return@Runnable
        }
        handler.postDelayed(tick, TICK_MS)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        createSession()
        val engine = TextToSpeech(this, this)
        tts = engine
        // 有些引擎（华为自带的）会在构造里**同步**回调 onInit，那一刻 tts 字段还没赋值，
        // 原来的 `val engine = tts ?: return` 会直接 return —— 语言和进度监听都没设上。
        // 结果就是：第一句能读（speak 本身不依赖监听），读完没有 onDone，整轮停在那一句。
        // 这里补一次，attachEngine 自己是幂等的。
        if (ready) attachEngine(engine)
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) {
            notifyState("语音引擎初始化失败")
            stopSelf()
            return
        }
        tts?.let { attachEngine(it) }
    }

    /** 把语言和进度监听挂到引擎上，只挂一次。 */
    private fun attachEngine(engine: TextToSpeech) {
        if (engineAttached) return
        engineAttached = true
        engine.language = Locale.US
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (!running || paused) return
                val gap = steps.getOrNull(index)?.pauseAfter ?: 800L
                // 回调不保证在主线程（各家引擎实现不同），统一挪回主线程再动状态
                handler.post { if (running && !paused) beginGap(gap) }
            }

            override fun onError(utteranceId: String?) {
                // 单句失败不该让整轮复习卡死，跳过继续
                if (!running || paused) return
                handler.post { if (running && !paused) beginGap(300L) }
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
                if (!running) { stopSelf(); return START_NOT_STICKY }
                if (paused) resume() else pause()
                updateNotification()
                return START_STICKY
            }

            ACTION_NEXT -> {
                if (!running) { stopSelf(); return START_NOT_STICKY }
                skipNext()
                return START_STICKY
            }

            ACTION_PREV -> {
                if (!running) { stopSelf(); return START_NOT_STICKY }
                skipPrev()
                return START_STICKY
            }

            ACTION_GAP_DONE -> {
                if (!running || paused || !inGap) return START_NOT_STICKY
                handler.removeCallbacks(tick)
                handler.removeCallbacks(gapDone)
                gapDone.run()
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
        // 跟读留白 8 秒：够把一句 5~8 个词的例句读完。说完静音 1.2 秒会自动收尾并推进，
        // 所以实际停顿通常只有 3~5 秒；不开口就等满 8 秒（跟「只听」模式的 0.5 秒区分开）
        val longGap = if (followGap) 8000L else 500L
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
        cachedPhraseIndex = -1
        currentLines = emptyList()
        currentCursor = 0
        running = true
        paused = false

        acquireWakeLock()
        requestFocus()
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
            out.add(Step(p.phrase, p.meaning, k(0.75f), 800L, i, p.id, p.phrase, "短语"))
            out.add(Step(p.phrase, p.meaning, k(1.0f), longGap, i, p.id, p.phrase, "短语"))
            if (phrasesOnly) return@forEachIndexed
            for (ex in p.examples) {
                out.add(Step(ex.text, ex.cn, k(0.85f), longGap, i, p.id, p.phrase, "例句"))
            }
            for (line in p.dialogue) {
                val rate = if (line.speaker == "B") 0.9f else 0.95f
                out.add(Step(line.text, line.cn, k(rate), 900L, i, p.id, p.phrase, line.speaker))
            }
            out.add(Step(p.phrase, p.meaning, k(1.0f), 1200L, i, p.id, p.phrase, "短语"))
        }
        return out
    }

    // ---------- 播放 ----------

    private fun playCurrent() {
        if (!running) return
        // 暂停状态下被「上一句 / 下一句」挪了位置：只把界面挪过去，等继续时再出声，
        // 否则下次 resume 会从旧位置重读，和屏幕上高亮的那行对不上。
        if (paused) {
            publish(false)
            updateNotification()
            return
        }
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

        // 行清单只在换短语时重建；行内位置每次都更新
        val start = steps.indexOfFirst { it.phraseIndex == step.phraseIndex }
        if (start >= 0 && cachedPhraseIndex != step.phraseIndex) {
            cachedPhraseIndex = step.phraseIndex
            val lines = ArrayList<ReviewBus.Line>()
            var i = start
            while (i < steps.size && steps[i].phraseIndex == step.phraseIndex) {
                lines.add(ReviewBus.Line(steps[i].text, steps[i].cn, steps[i].tag))
                i++
            }
            currentLines = lines
        }
        currentCursor = if (start >= 0) (index - start).coerceAtLeast(0) else 0

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
        val ok = engine.speak(step.text, TextToSpeech.QUEUE_FLUSH, null, "s$index")
        publish(false)
        updateNotification()

        // 引擎拒收（正在初始化、被打断、或干脆坏了）时不会有 onDone 回调，
        // 之前就整轮卡死在这一句。这里当成「这句读完了」，直接进留白继续往下走。
        if (ok != TextToSpeech.SUCCESS) beginGap(300L)
    }

    private fun beginGap(ms: Long) {
        inGap = true
        gapTotal = ms
        gapRemain = ms
        gapDeadline = SystemClock.elapsedRealtime() + ms
        publish(true)
        handler.removeCallbacks(tick)
        handler.removeCallbacks(gapDone)
        handler.postDelayed(gapDone, ms)     // 推进：绝对时间到点执行，与倒数无关
        handler.postDelayed(tick, TICK_MS)   // 显示：只负责画圆环
    }

    private fun advance() {
        index += 1
        playCurrent()
    }

    private fun pause() {
        if (!running || paused) return
        paused = true
        handler.removeCallbacks(tick)
        handler.removeCallbacks(gapDone)
        if (inGap) {
            // 暂停期间不流逝，恢复时按剩余时间重新排
            gapRemain = (gapDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } else {
            tts?.stop()
        }
        releaseWakeLock()
        publish(inGap)
        updateSessionState()
    }

    private fun resume() {
        if (!running || !paused) return
        paused = false
        acquireWakeLock()
        if (inGap) {
            beginGap(gapRemain.coerceAtLeast(0L))
            publish(true)
        } else {
            playCurrent()
        }
        updateSessionState()
    }

    /**
     * 按「条」跳：整条短语一起跳过或退回，不是跳到下一句例句。
     * 骑车时按一下是想换个词，不该连按七八下把例句和对话都划过去。
     *
     * 目标落在该短语的**第一个** Step 上，所以会从慢速版重新开始，保持一条短语的完整节奏。
     */
    private fun skipPhrase(toNext: Boolean) {
        if (!running) return
        val cur = steps.getOrNull(index)?.phraseIndex ?: return
        val want = if (toNext) cur + 1 else (cur - 1).coerceAtLeast(0)
        val target = steps.indexOfFirst { it.phraseIndex == want }
        // 已经是最后一条再点「下一条」就原地不动：宁可没反应，也不要误触直接结束整轮
        if (target < 0) return
        handler.removeCallbacks(tick)
        handler.removeCallbacks(gapDone)
        inGap = false
        tts?.stop()
        index = target
        playCurrent()
    }

    private fun skipNext() = skipPhrase(true)

    private fun skipPrev() = skipPhrase(false)

    private fun finish() {
        running = false
        paused = false
        inGap = false
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        releaseWakeLock()
        abandonFocus()
        session?.isActive = false
        ReviewBus.reset()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        abandonFocus()
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
                phraseId = step?.phraseId ?: 0L,
                phrase = step?.phraseTitle.orEmpty(),
                scopeLabel = scopeLabel,
                stepIndex = index + 1,
                stepTotal = steps.size,
                lines = currentLines,
                cursor = currentCursor
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
                phraseId = step?.phraseId ?: 0L,
                phrase = step?.phraseTitle.orEmpty(),
                stepIndex = index + 1,
                stepTotal = steps.size,
                lines = currentLines,
                cursor = currentCursor
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
                override fun onSkipToPrevious() = skipPrev()
                override fun onStop() = finish()
            })
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            // 锁屏/系统媒体面板点一下能回到 App，否则按了没地方可去
            setSessionActivity(
                PendingIntent.getActivity(
                    this@ReviewService, 0,
                    Intent(this@ReviewService, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            isActive = true
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(
                        PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        1f
                    )
                    .setActions(SESSION_ACTIONS)
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
                .setActions(SESSION_ACTIONS)
                .build()
        )
    }

    // ---------- 唤醒锁 / 音频焦点 ----------

    /**
     * 留白期间没有音频在播，CPU 会进休眠，Handler 的定时任务跟着停 —— 锁屏听复习
     * 就会卡在两句之间。这里持一把 PARTIAL_WAKE_LOCK，只保 CPU 不睡、不亮屏。
     * 带 1 小时超时兜底，忘了点结束也不会通宵耗电。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpeakLoop:review").apply {
            setReferenceCounted(false)
            acquire(60L * 60L * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * 申请音频焦点做两件事：来电话 / 微信语音时自动让路，
     * 以及让系统把蓝牙耳机和线控的播放键路由到我们这个会话。
     */
    private fun requestFocus() {
        if (focusRequest != null) return
        val am = getSystemService(AudioManager::class.java) ?: return
        audioManager = am
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    if (running && !paused) {
                        pause()
                        updateNotification()
                    }
                }
            }
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        val am = audioManager
        val req = focusRequest
        if (am != null && req != null) am.abandonAudioFocusRequest(req)
        focusRequest = null
        audioManager = null
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
        val prevPending = PendingIntent.getService(
            this, 4, Intent(this, ReviewService::class.java).setAction(ACTION_PREV),
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

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(sub)
            .setSubText(if (paused) "已暂停" else null)
            .setSmallIcon(R.drawable.ic_play)
            // 让通知的强调色跟着 App 走，不再用系统默认的灰蓝
            .setColor(Ui.ACCENT)
            .setOngoing(running)
            .setContentIntent(openPending)
            // 图标不能传 0：华为这类 ROM 会把图标为 0 的 action 当无效项整条丢掉，
            // 表现就是「通知栏里暂停、下一句都没了」。统一用项目自己的单色矢量图。
            .addAction(R.drawable.ic_prev, "上一条", prevPending)
            .addAction(
                if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                if (paused) "继续" else "暂停",
                togglePending
            )
            .addAction(R.drawable.ic_next, "下一条", nextPending)
            .addAction(R.drawable.ic_stop, "停止", stopPending)

        // MediaStyle + sessionToken 是蓝牙耳机能控制的前提：只做普通通知时，
        // 系统媒体面板和 AVRCP 不知道有这个播放会话，耳机上按暂停自然没反应。
        session?.let { s ->
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(s.sessionToken)
                    // 折叠时露三个：上一句 / 暂停 / 下一句。索引对应上面 addAction 的顺序
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
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

    /**
     * 服务内部的提示（「这一天没有可练的短语」这类）。
     *
     * 以前这里发一条 ID+1 的通知，但从来没有人撤它 —— 结果这条提示会永久卡在通知栏，
     * 而且里面的「暂停 / 下一条」按钮对应的服务早就停了，点了毫无反应。
     * 改成 Toast：一闪而过，不留垃圾。
     */
    private fun notifyState(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
